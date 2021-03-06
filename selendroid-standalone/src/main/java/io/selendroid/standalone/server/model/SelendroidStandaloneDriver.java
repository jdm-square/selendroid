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
package io.selendroid.standalone.server.model;

import com.beust.jcommander.internal.Lists;
import com.google.common.base.Function;
import io.netty.handler.codec.http.HttpMethod;
import io.selendroid.common.SelendroidCapabilities;
import io.selendroid.server.common.ServerDetails;
import io.selendroid.server.common.exceptions.AppCrashedException;
import io.selendroid.server.common.exceptions.SelendroidException;
import io.selendroid.server.common.exceptions.SessionNotCreatedException;
import io.selendroid.standalone.SelendroidConfiguration;
import io.selendroid.standalone.android.AndroidApp;
import io.selendroid.standalone.android.AndroidDevice;
import io.selendroid.standalone.android.AndroidEmulator;
import io.selendroid.standalone.android.AndroidSdk;
import io.selendroid.standalone.android.DeviceManager;
import io.selendroid.standalone.android.InstrumentationProcessListener;
import io.selendroid.standalone.android.InstrumentationProcessOutput;
import io.selendroid.standalone.android.impl.DefaultAndroidEmulator;
import io.selendroid.standalone.android.impl.DefaultDeviceManager;
import io.selendroid.standalone.android.impl.DefaultHardwareDevice;
import io.selendroid.standalone.android.impl.InstalledAndroidApp;
import io.selendroid.standalone.builder.AndroidDriverAPKBuilder;
import io.selendroid.standalone.builder.SelendroidServerBuilder;
import io.selendroid.standalone.exceptions.AndroidDeviceException;
import io.selendroid.standalone.exceptions.AndroidSdkException;
import io.selendroid.standalone.server.util.FolderMonitor;
import io.selendroid.standalone.server.util.HttpClientUtil;
import io.selendroid.server.common.exceptions.AppCrashedException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.apache.commons.io.FileUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.remote.BrowserType;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.TimeUnit;

public class SelendroidStandaloneDriver
  implements ServerDetails, InstrumentationProcessListener {
  public static final String WD_RESP_KEY_VALUE = "value";
  public static final String WD_RESP_KEY_STATUS = "status";
  public static final String WD_RESP_KEY_SESSION_ID = "sessionId";
  public static final String APP_BASE_PACKAGE = "basePackage";
  public static final String APP_ID = "appId";
  private static int selendroidServerPort = 38080;
  private static final Logger log = Logger.getLogger(SelendroidStandaloneDriver.class.getName());
  private Map<String, AndroidApp> appsStore = new HashMap<String, AndroidApp>();
  private Map<String, AndroidApp> selendroidServers = new HashMap<String, AndroidApp>();
  private Map<String, ActiveSession> sessions = new HashMap<String, ActiveSession>();
  private DeviceStore deviceStore = null;
  private SelendroidServerBuilder selendroidApkBuilder = null;
  private AndroidDriverAPKBuilder androidDriverAPKBuilder = null;
  private SelendroidConfiguration serverConfiguration = null;
  private DeviceManager deviceManager;
  private FolderMonitor folderMonitor = null;
  private SelendroidStandaloneDriverEventListener eventListener
      = new DummySelendroidStandaloneDriverEventListener();
  private AtomicBoolean instrumentationProcessFinished = new AtomicBoolean(false);
  private String instrumentationProcessOutput;
  private Exception instrumentationProcessError;


  public SelendroidStandaloneDriver(SelendroidConfiguration serverConfiguration)
      throws AndroidSdkException, AndroidDeviceException {
    this.serverConfiguration = serverConfiguration;
    selendroidApkBuilder = new SelendroidServerBuilder(serverConfiguration);
    androidDriverAPKBuilder = new AndroidDriverAPKBuilder();

    selendroidServerPort = serverConfiguration.getSelendroidServerPort();

    if (serverConfiguration.getAppFolderToMonitor() != null) {
      startFolderMonitor();
    }
    initApplicationsUnderTest(serverConfiguration);
    initAndroidDevices();
    deviceStore.setClearData(!serverConfiguration.isNoClearData());
    deviceStore.setKeepEmulator(serverConfiguration.isKeepEmulator());
  }

  /**
   * For testing only
   */
  SelendroidStandaloneDriver(SelendroidServerBuilder builder, DeviceManager deviceManager,
                             AndroidDriverAPKBuilder androidDriverAPKBuilder) {
    this.selendroidApkBuilder = builder;
    this.deviceManager = deviceManager;
    this.androidDriverAPKBuilder = androidDriverAPKBuilder;
  }

  /**
   * This function will sign an android app and add it to the App Store. The function is made public because it also be
   * invoked by the Folder Monitor each time a new application dropped into this folder.
   *
   * @param file
   *          - The file to be added to the app store
   * @throws AndroidSdkException
   */
  public void addToAppsStore(File file) throws AndroidSdkException {
    AndroidApp app = null;
    try {
      app = selendroidApkBuilder.resignApp(file);
    } catch (Exception e) {
      throw new SessionNotCreatedException(
          "An error occurred while resigning the app '" + file.getName()
              + "'. ", e);
    }
    String appId = null;
    try {
      appId = app.getAppId();
    } catch (AndroidSdkException e) {
      log.info("Ignoring app because an error occurred reading the app details: "
          + file.getAbsolutePath());
      log.info(e.getMessage());
    }
    if (appId != null && !appsStore.containsKey(appId)) {
      appsStore.put(appId, app);

      log.info("App " + appId
          + " has been added to selendroid standalone server.");
    }
  }

  /* package */void initApplicationsUnderTest(SelendroidConfiguration serverConfiguration)
      throws AndroidSdkException {
    if (serverConfiguration == null) {
      throw new SelendroidException("Configuration error - serverConfiguration can't be null.");
    }
    this.serverConfiguration = serverConfiguration;

    // each of the apps specified on the command line need to get resigned
    // and 'stored' to be installed on the device
    for (String appPath : serverConfiguration.getSupportedApps()) {
      File file = new File(appPath);
      if (file.exists()) {
        addToAppsStore(file);
      } else {
        log.severe("Ignoring app because it was not found: " + file.getAbsolutePath());
      }
    }

    if (!serverConfiguration.isNoWebViewApp()) {
      // extract the 'AndroidDriver' app and show it as available
      try {
        // using "android" as the app name, because that is the desired capability default in
        // selenium for
        // DesiredCapabilities.ANDROID
        File androidAPK = androidDriverAPKBuilder.extractAndroidDriverAPK();
        if(serverConfiguration != null && serverConfiguration.isDeleteTmpFiles()) {
          androidAPK.deleteOnExit(); //Deletes temporary files if flag set
        }
        AndroidApp app =
            selendroidApkBuilder.resignApp(androidAPK);
        appsStore.put(BrowserType.ANDROID, app);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  /* package */void initAndroidDevices() throws AndroidDeviceException {
    deviceManager =
        new DefaultDeviceManager(AndroidSdk.adb().getAbsolutePath(),
            serverConfiguration.shouldKeepAdbAlive());
    deviceStore = new DeviceStore(serverConfiguration.getEmulatorPort(), deviceManager);
    deviceStore.initAndroidDevices(new DefaultHardwareDeviceListener(deviceStore, this),
        serverConfiguration.shouldKeepAdbAlive());
  }

  @Override
  public String getServerVersion() {
    return SelendroidServerBuilder.getJarVersionNumber();
  }

  @Override
  public String getCpuArch() {
    return System.getProperty("os.arch");
  }

  @Override
  public String getOsVersion() {
    return System.getProperty("os.version");
  }

  @Override
  public String getOsName() {
    return System.getProperty("os.name");
  }

  protected SelendroidConfiguration getSelendroidConfiguration() {
    return serverConfiguration;
  }

  public String createNewTestSession(JSONObject caps) {
    return createNewTestSession(caps, serverConfiguration.getServerStartRetries());
  }

  public String createNewTestSession(JSONObject caps, Integer retries) {
    AndroidDevice device = null;
    AndroidApp app = null;
    Exception lastException = null;
    while (retries >= 0) {
      try {
        SelendroidCapabilities desiredCapabilities = getSelendroidCapabilities(caps);
        String desiredAut = desiredCapabilities.getDefaultApp(appsStore.keySet());
        app = getAndroidApp(desiredCapabilities, desiredAut);
        log.info("'" + desiredAut + "' will be used as app under test.");
        device = deviceStore.findAndroidDevice(desiredCapabilities);

        // If we are using an emulator need to start it up
        if (device instanceof AndroidEmulator) {
          startAndroidEmulator(desiredCapabilities, (AndroidEmulator) device);
          // If we are using an android device
        } else {
          device.unlockScreen();
        }

        boolean appInstalledOnDevice = device.isInstalled(app) || app instanceof InstalledAndroidApp;
        if (!appInstalledOnDevice || serverConfiguration.isForceReinstall()) {
          device.install(app);
        } else {
          log.info("the app under test is already installed.");
        }

        if(!serverConfiguration.isNoClearData()) {
          device.clearUserData(app);
        }

        int port = serverConfiguration.isReuseSelendroidServerPort()
            ? serverConfiguration.getSelendroidServerPort()
            : getNextSelendroidServerPort();

        boolean serverInstalled = device.isInstalled("io.selendroid." + app.getBasePackage());
        if (!serverInstalled || serverConfiguration.isForceReinstall()) {
          try {
            device.install(createSelendroidServerApk(app));
          } catch (AndroidSdkException e) {
            throw new SessionNotCreatedException("Could not install selendroid-server on the device", e);
          }
        } else {
          log.info(
              "Not creating and installing selendroid-server because it is already installed for this app under test.");
        }

        // Run any adb commands requested in the capabilities
        List<String> preSessionAdbCommands = desiredCapabilities.getPreSessionAdbCommands();
        runPreSessionCommands(device, preSessionAdbCommands);

        // Push extension dex to device if specified
        String extensionFile = desiredCapabilities.getSelendroidExtensions();
        pushExtensionsToDevice(device, extensionFile);

        pushExtraArgsToDevice(device, desiredCapabilities);

        // Configure logging on the device
        device.setLoggingEnabled(serverConfiguration.isDeviceLog());

        // It's GO TIME!
        // start the selendroid server on the device and make sure it's up
        eventListener.onBeforeDeviceServerStart();
        device.startSelendroid(app, port, desiredCapabilities);
        device.addInstrumentationProcessListener(this);
        waitForServerStart(device);
        eventListener.onAfterDeviceServerStart();

        // arbitrary sleeps? yay...
        // looks like after the server starts responding
        // we need to give it a moment before starting a session?
        try {
          Thread.sleep(500);
        } catch (InterruptedException e1) {
          Thread.currentThread().interrupt();
        }

        // create the new session on the device server
        RemoteWebDriver driver =
          new RemoteWebDriver(new URL("http://localhost:" + device.getSelendroidsPort() + "/wd/hub"), desiredCapabilities);
        String sessionId = driver.getSessionId().toString();
        SelendroidCapabilities requiredCapabilities =
          new SelendroidCapabilities(driver.getCapabilities().asMap());
        ActiveSession session =
          new ActiveSession(sessionId, requiredCapabilities, app, device, port, this);

        this.sessions.put(sessionId, session);

        // We are requesting an "AndroidDriver" so automatically switch to the webview
        if (BrowserType.ANDROID.equals(desiredCapabilities.getAut())) {
          switchToWebView(driver);
        }

        return sessionId;
      } catch (Exception e) {
        lastException = e;
        log.log(Level.SEVERE, "Error occurred while starting Selendroid session", e);
        retries--;

        // Return device to store
        if (device != null) {
          deviceStore.release(device, app);
          device = null;
        }
      }
    }

    if (lastException instanceof RuntimeException) {
      // Don't wrap the exception
      throw (RuntimeException)lastException;
    } else {
      throw new SessionNotCreatedException("Error starting Selendroid session", lastException);
    }
  }

  private void switchToWebView(RemoteWebDriver driver) {
    // arbitrarily high wait time, will this cover our slowest possible device/emulator?
    WebDriverWait wait = new WebDriverWait(driver, 60);
    // wait for the WebView to appear
    wait.until(ExpectedConditions.visibilityOfElementLocated(By.className("android.webkit.WebView")));
    driver.switchTo().window("WEBVIEW");
    // the 'android-driver' webview has an h1 with id 'AndroidDriver' embedded in it
    wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("AndroidDriver")));
  }

  private void waitForServerStart(final AndroidDevice device) {
    log.info("Waiting for the Selendroid server to start.");

    Wait<AndroidDevice> wait = new FluentWait<AndroidDevice>(device)
      .withTimeout(
        serverConfiguration.getServerStartTimeout(),
        TimeUnit.MILLISECONDS)
      .pollingEvery(
        serverConfiguration.getServerStartPollingInterval(),
        TimeUnit.MILLISECONDS);
    try {
      wait.until(new Function<AndroidDevice, Boolean>() {
        @Override
        public Boolean apply(AndroidDevice device) {
          if (!instrumentationProcessFinished()) {
            return device.isSelendroidRunning();
          }

          InstrumentationProcessOutput instrumentationOutput =
	           InstrumentationProcessOutput.parse(
              getInstrumentationProcessOutput());
          final Exception instrumentationError = getInstrumentationProcessError();
          if (instrumentationError != null) {
            throw new SelendroidException(
              "Failed to execute instrument command, full output:\n" +
              instrumentationOutput.getFullOutput(),
              instrumentationError);
          }

          throw InstrumentationProcessOutput.getInstrumentationProcessError(
            instrumentationOutput,
            device);
        }
      });
    } catch (TimeoutException e) {
      throw new SelendroidException(
        "Selendroid server didn't come up on the device after "
        + serverConfiguration.getServerStartTimeout() / 1000
        + " seconds");
    }

    log.info("Selendroid server has started.");
  }

  private void pushExtraArgsToDevice(
    AndroidDevice device,
    SelendroidCapabilities caps
  ) {
    String devicePath = new File(
      device.getExternalStoragePath(),
      "extra_args.json"
    ).getAbsolutePath();

    // Make sure we clean the file in case we don't push a new one
    log.info("Clearing extra args file from device");
    device.runAdbCommand(String.format("shell rm %s", devicePath));

    if (!caps.hasExtraAUTArgs()) {
      return;
    }

    try {
      final String fileContents = caps.getExtraAUTArgs().toString();
      File temp = File.createTempFile("extra_args", ".json");
      FileUtils.write(temp, fileContents);

      log.info("Pushing new extra args file to device");
      device.runAdbCommand(
        String.format("push %s %s", temp.getAbsolutePath(), devicePath)
      );
    } catch(IOException e) {
      throw new RuntimeException("Error while writing extra args to disk", e);
    }
  }

  private void pushExtensionsToDevice(AndroidDevice device, String extensionFile) {
    if (extensionFile != null) {
      String externalStorageDirectory = device.getExternalStoragePath();
      String deviceDexPath = new File(externalStorageDirectory, "extension.dex").getAbsolutePath();
      device.runAdbCommand(String.format("push %s %s", extensionFile, deviceDexPath));
    }
  }

  private void runPreSessionCommands(AndroidDevice device, List<String> preSessionAdbCommands) {
    List<String> adbCommands = new ArrayList<String>();
    adbCommands.add("shell setprop log.tag.SELENDROID " + serverConfiguration.getLogLevel().name());
    adbCommands.addAll(preSessionAdbCommands);

    for (String adbCommandParameter : adbCommands) {
      device.runAdbCommand(adbCommandParameter);
    }
  }

  private void startAndroidEmulator(SelendroidCapabilities desiredCapabilities, AndroidEmulator device) throws AndroidDeviceException {
    AndroidEmulator emulator = device;
    if (emulator.isEmulatorStarted()) {
      emulator.unlockScreen();
    } else {
      Map<String, Object> config = new HashMap<String, Object>();
      if (serverConfiguration.getEmulatorOptions() != null) {
        config.put(AndroidEmulator.EMULATOR_OPTIONS, serverConfiguration.getEmulatorOptions());
      }
      config.put(AndroidEmulator.TIMEOUT_OPTION, serverConfiguration.getTimeoutEmulatorStart());
      if (desiredCapabilities.asMap().containsKey(SelendroidCapabilities.DISPLAY)) {
        Object d = desiredCapabilities.getCapability(SelendroidCapabilities.DISPLAY);
        config.put(AndroidEmulator.DISPLAY_OPTION, String.valueOf(d));
      }

      Locale locale = parseLocale(desiredCapabilities);
      emulator.start(locale, deviceStore.nextEmulatorPort(), config);
    }
    log.info("XXXjdm: trying to set emulator's device based on " + emulator.getAvdName());
    emulator.setIDevice(deviceManager.getVirtualDevice(emulator.getAvdName()));
  }

  private AndroidApp getAndroidApp(SelendroidCapabilities desiredCapabilities, String aut) {
    AndroidApp app = appsStore.get(aut);
    if (app == null) {
      if (desiredCapabilities.getLaunchActivity() != null) {
        String appInfo = String.format("%s/%s", aut, desiredCapabilities.getLaunchActivity());
        log.log(Level.INFO, "The requested application under test is not configured in selendroid server, " +
            "assuming the " + appInfo + " is installed on the device.");
        app = new InstalledAndroidApp(appInfo);
      } else {
        throw new SessionNotCreatedException(
            "The requested application under test is not configured in selendroid server.");
      }
    }
    // adjust app based on capabilities (some parameters are session specific)
    app = augmentApp(app, desiredCapabilities);
    return app;
  }

  private SelendroidCapabilities getSelendroidCapabilities(JSONObject caps) {
    SelendroidCapabilities desiredCapabilities;// Convert the JSON capabilities to SelendroidCapabilities
    try {
      desiredCapabilities = new SelendroidCapabilities(caps);
      desiredCapabilities.setUseJunitRunner(serverConfiguration.isUseJUnitBootstrap());
    } catch (JSONException e) {
      throw new SelendroidException("Desired capabilities cannot be parsed.");
    }
    return desiredCapabilities;
  }

  /**
   * Augment the application with parameters from {@code desiredCapabilities}
   *
   * @param app                 to be augmented
   * @param desiredCapabilities configuration requested for this session
   */
  private AndroidApp augmentApp(AndroidApp app, SelendroidCapabilities desiredCapabilities) {
    if (desiredCapabilities.getLaunchActivity() != null) {
      app.setMainActivity(desiredCapabilities.getLaunchActivity());
    }
    return app;
  }

  private AndroidApp createSelendroidServerApk(AndroidApp aut) throws AndroidSdkException {
    if (!selendroidServers.containsKey(aut.getAppId())) {
      try {
        AndroidApp selendroidServer = selendroidApkBuilder.createSelendroidServer(aut);
        selendroidServers.put(aut.getAppId(), selendroidServer);
      } catch (Exception e) {
        log.log(Level.SEVERE, "Cannot build the Selendroid server APK", e);
        throw new SessionNotCreatedException(
            "Cannot build the Selendroid server APK for application '" + aut + "': " + e.getMessage());
      }
    }
    return selendroidServers.get(aut.getAppId());
  }

  private Locale parseLocale(SelendroidCapabilities capa) {
    if (capa.getLocale() == null) {
      return null;
    }
    String[] localeStr = capa.getLocale().split("_");

    return new Locale(localeStr[0], localeStr[1]);
  }

  // This function will start a separate thread to monitor the
  // Applications folder.
  private void startFolderMonitor() {
    if (serverConfiguration.getAppFolderToMonitor() != null) {
      try {
        folderMonitor = new FolderMonitor(this, serverConfiguration);
        folderMonitor.start();
      } catch (IOException e) {
        log.warning("Could not monitor the given folder: "
            + serverConfiguration.getAppFolderToMonitor());
      }
    }
  }

  /**
   * For testing only
   */
  /* package */Map<String, AndroidApp> getConfiguredApps() {
    return Collections.unmodifiableMap(appsStore);
  }

  /**
   * For testing only
   */
  /* package */void setDeviceStore(DeviceStore store) {
    this.deviceStore = store;
  }

  private synchronized int getNextSelendroidServerPort() {
    return selendroidServerPort++;
  }

  /**
   * FOR TESTING ONLY
   */
  public List<ActiveSession> getActiveSessions() {
    return Lists.newArrayList(sessions.values());
  }

  public boolean isValidSession(String sessionId) {
    return sessionId != null && !sessionId.isEmpty() && sessions.containsKey(sessionId);
  }

  public void stopSession(String sessionId) throws AndroidDeviceException {
    if (isValidSession(sessionId)) {
      ActiveSession session = sessions.get(sessionId);
      session.stopSessionTimer();
      try {
        HttpClientUtil.executeRequest(
            "http://localhost:" + session.getSelendroidServerPort() + "/wd/hub/session/" + sessionId,
            HttpMethod.DELETE);
      } catch (Exception e) {
        log.log(Level.WARNING, "Error stopping session, safe to ignore", e);
      }
      deviceStore.release(session.getDevice(), session.getAut());
      sessions.remove(sessionId);
    }
  }

  public void quitSelendroid() {
    List<String> sessionsToQuit = Lists.newArrayList(sessions.keySet());
    if (!sessionsToQuit.isEmpty()) {
      for (String sessionId : sessionsToQuit) {
        try {
          stopSession(sessionId);
        } catch (AndroidDeviceException e) {
          log.log(Level.SEVERE, "Error occurred while stopping session", e);
        }
      }
    }
    deviceManager.shutdown();
  }

  public SelendroidCapabilities getSessionCapabilities(String sessionId) {
    if (sessions.containsKey(sessionId)) {
      return sessions.get(sessionId).getDesiredCapabilities();
    }
    return null;
  }

  public ActiveSession getActiveSession(String sessionId) {
    if (sessionId != null && sessions.containsKey(sessionId)) {
      return sessions.get(sessionId);
    }

    return null;
  }

  @Override
  public synchronized JSONArray getSupportedApps() {
    JSONArray list = new JSONArray();
    for (AndroidApp app : appsStore.values()) {
      JSONObject appInfo = new JSONObject();
      try {
        appInfo.put(APP_ID, app.getAppId());
        appInfo.put(APP_BASE_PACKAGE, app.getBasePackage());
        appInfo.put("mainActivity", app.getMainActivity());
        list.put(appInfo);
      } catch (Exception e) {
      }
    }
    return list;
  }

  @Override
  public synchronized JSONArray getSupportedDevices() {
    JSONArray list = new JSONArray();
    for (AndroidDevice device : deviceStore.getDevices()) {
      JSONObject deviceInfo = new JSONObject();
      try {
        if (device instanceof DefaultAndroidEmulator) {
          deviceInfo.put(SelendroidCapabilities.EMULATOR, true);
          deviceInfo.put("avdName", ((DefaultAndroidEmulator) device).getAvdName());
        } else {
          deviceInfo.put(SelendroidCapabilities.EMULATOR, false);
          deviceInfo.put(SelendroidCapabilities.MODEL, ((DefaultHardwareDevice) device).getModel());
          deviceInfo.put(SelendroidCapabilities.SERIAL,((DefaultHardwareDevice) device).getSerial());
        }
        deviceInfo.put(SelendroidCapabilities.API_TARGET_TYPE,
            device.getAPITargetType());
        deviceInfo
          .put(SelendroidCapabilities.PLATFORM_VERSION, device.getTargetPlatform().getApi());
        deviceInfo.put(SelendroidCapabilities.SCREEN_SIZE, device.getScreenSize());

        list.put(deviceInfo);
      } catch (Exception e) {
        log.log(Level.WARNING, "Error occurred when building supported device info", e);
      }
    }
    return list;
  }

  protected ActiveSession findActiveSession(AndroidDevice device) {
    for (ActiveSession session : sessions.values()) {
      if (session.getDevice().equals(device)) {
        return session;
      }
    }
    return null;

  }

  public byte[] takeScreenshot(String sessionId) throws AndroidDeviceException {
    if (sessionId == null || !sessions.containsKey(sessionId)) {
      throw new SelendroidException("The given session id '" + sessionId + "' was not found.");
    }
    return sessions.get(sessionId).getDevice().takeScreenshot();
  }

  public void setEventListener(SelendroidStandaloneDriverEventListener eventListener) {
    this.eventListener = eventListener;
  }

  private boolean instrumentationProcessFinished() {
    return instrumentationProcessFinished.get();
  }

  private String getInstrumentationProcessOutput() {
    return instrumentationProcessOutput;
  }

  private Exception getInstrumentationProcessError() {
    return instrumentationProcessError;
  }

  @Override
  public void onInstrumentationProcessComplete(String output) {
    instrumentationProcessFinished.set(true);
    instrumentationProcessOutput = output;
  }

  @Override
  public void onInstrumentationProcessFailed(String output, Exception error) {
    instrumentationProcessFinished.set(true);
    instrumentationProcessOutput = output;
    instrumentationProcessError = error;
  }
}
