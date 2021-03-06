/*
 * Copyright 2012-2014 eBay Software Foundation and selendroid committers.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.selendroid.standalone.android;

import com.google.common.base.Function;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.support.ui.FluentWait;

import io.selendroid.server.common.exceptions.AppCrashedException;
import io.selendroid.server.common.exceptions.SelendroidException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

public class InstrumentationProcessOutput {
  private String errorMessage;
  private String shortMessage;
  private String longMessage;
  private String fullOutput;

  private static final String REGULAR_CRASH_MESSAGE = "Process crashed.";
  private static final String NATIVE_CRASH_MESSAGE = "Native crash";

  private static final String SHORT_MESSAGE_REGEX =
    "INSTRUMENTATION_RESULT: shortMsg=(.+)";
  private static final String LONG_MESSAGE_REGEX =
    "INSTRUMENTATION_RESULT: longMsg=(.+)";
  private static final String ERROR_MESSAGE_REGEX =
    "INSTRUMENTATION_STATUS: Error=(.+)";

  private static final long CRASH_LOG_TIMEOUT_MS = 2000;
  public static final String CRASH_LOG_TIMEOUT_ERROR_MESSAGE =
    "Failed to get stacktrace, see Logcat for more details";

  public static final String INSTRUMENTATION_PROCESS_FAILED_ERROR_MESSAGE =
    "Instrumentation process failed";
  public static final String APP_NOT_INSTALLED_ERROR_MESSAGE =
    "Could not start the app under test using instrumentation. " +
    "Is the correct app under test installed? Read the details below";


  public static InstrumentationProcessOutput parse(String output) {
    Matcher shortMessageMatcher =
      Pattern
        .compile(SHORT_MESSAGE_REGEX)
        .matcher(output);
    Matcher longMessageMatcher =
      Pattern
        .compile(LONG_MESSAGE_REGEX)
        .matcher(output);
    Matcher errorMessageMatcher =
      Pattern
        .compile(ERROR_MESSAGE_REGEX)
        .matcher(output);

    String shortMessage;
    if (shortMessageMatcher.find()) {
      shortMessage = shortMessageMatcher.group(1);
    } else {
      shortMessage = null;
    }

    String longMessage;
    if (longMessageMatcher.find()) {
      longMessage = longMessageMatcher.group(1);
    } else {
      longMessage = null;
    }

    String errorMessage;
    if (errorMessageMatcher.find()) {
      errorMessage = errorMessageMatcher.group(1);
    } else {
      errorMessage = null;
    }

    return (new InstrumentationProcessOutput())
      .setFullOutput(output)
      .setShortMessage(shortMessage)
      .setLongMessage(longMessage)
      .setErrorMessage(errorMessage);
  }

  public boolean isAppCrash() {
    return isRegularAppCrash() || isNativeCrash();
  }

  public boolean isRegularAppCrash() {
    return REGULAR_CRASH_MESSAGE.equals(shortMessage);
  }

  public boolean isNativeCrash() {
    return NATIVE_CRASH_MESSAGE.equals(shortMessage);
  }

  public String getMessage() {
    if (isNativeCrash()) {
      return longMessage;
    }
    if (isRegularAppCrash()) {
      return shortMessage;
    }

    if (errorMessage != null) {
      return errorMessage;
    } else if (shortMessage != null) {
      String message = shortMessage;
      if (longMessage != null) {
        message += "\n" + longMessage;
      }
      return message;
    }

    return fullOutput;
  }

  public String getFullOutput() {
    return fullOutput;
  }

  private InstrumentationProcessOutput() {
  }

  private InstrumentationProcessOutput setFullOutput(String fullOutput) {
    this.fullOutput = fullOutput;
    return this;
  }

  private InstrumentationProcessOutput setShortMessage(String shortMessage) {
    this.shortMessage = shortMessage;
    return this;
  }

  private InstrumentationProcessOutput setLongMessage(String longMessage) {
    this.longMessage = longMessage;
    return this;
  }

  private InstrumentationProcessOutput setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
    return this;
  }

  public static final SelendroidException getInstrumentationProcessError(
    InstrumentationProcessOutput instrumentationOutput,
    final AndroidDevice device) {
    String logcat = null;
    try {
      File f = new File(System.getProperty("java.io.tmpdir"), "logcat-" + UUID.randomUUID().toString());
      FileUtils.writeLines(f, device.getLogs());
      logcat = f.getAbsolutePath();
    } catch (IOException e) {
      // ignore
    }

    if (instrumentationOutput.isNativeCrash()) {
      return new AppCrashedException(
        INSTRUMENTATION_PROCESS_FAILED_ERROR_MESSAGE +
        ": " +
        instrumentationOutput.getMessage() +
        "\nSee logcat for more details" + (logcat != null ? " (" + logcat + ")" : ""));
    }

    if (instrumentationOutput.isRegularAppCrash()) {
      // There's a race condition between the instrumentation process
      // terminating and the crash logs being written on the device
      // so we wait a certain time in order to be able to provide a stacktrace
      // for the crash
      String errorMessage = waitForCrashLog(device);
      if (errorMessage == null) {
        errorMessage = INSTRUMENTATION_PROCESS_FAILED_ERROR_MESSAGE +
          ": " +
          instrumentationOutput.getMessage() + "\n" +
          CRASH_LOG_TIMEOUT_ERROR_MESSAGE + (logcat != null ? " (" + logcat + ")" : "");
      }

      return new AppCrashedException(errorMessage);
    }

    // Special case for when the app under test is not installed
    if (instrumentationOutput
        .getMessage()
        .contains("Unable to find instrumentation target package")) {
      return new SelendroidException(
        APP_NOT_INSTALLED_ERROR_MESSAGE +
        ":\n" +
        instrumentationOutput.getFullOutput());
    }

    return new SelendroidException(
      INSTRUMENTATION_PROCESS_FAILED_ERROR_MESSAGE +
      ": " +
      instrumentationOutput.getMessage() +
      "\nSee full output for more details:\n" +
      instrumentationOutput.getFullOutput());

  }

  private static String waitForCrashLog(final AndroidDevice device) {
    try {
      return (new FluentWait<AndroidDevice>(device))
        .withTimeout(
          CRASH_LOG_TIMEOUT_MS,
          TimeUnit.MILLISECONDS)
        .until(
          new Function<AndroidDevice, String>() {
            @Override
            public String apply(AndroidDevice device) {
              // getCrashLog is not nullable
              String crashLog = device.getCrashLog();
              return crashLog.isEmpty() ? null : crashLog;
            }
          }
        );
    } catch (TimeoutException e) {
      return null;
    }
  }
}
