/*
MIT License

Copyright (c) 2020 Dipjyoti Metia

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package com.core;

import com.config.AppConfig;
import com.typesafe.config.ConfigFactory;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.remote.MobileCapabilityType;
import lombok.extern.slf4j.Slf4j;
import net.lightbody.bmp.BrowserMobProxyServer;
import net.lightbody.bmp.client.ClientUtil;
import net.lightbody.bmp.core.har.Har;
import net.lightbody.bmp.proxy.CaptureType;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.devicefarm.DeviceFarmClient;
import software.amazon.awssdk.services.devicefarm.model.CreateTestGridUrlRequest;
import software.amazon.awssdk.services.devicefarm.model.CreateTestGridUrlResponse;

import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.URI;
import java.net.URL;

@Slf4j
public class DriverController extends WebOptions {
    private static final AppConfig appConfig = new AppConfig(ConfigFactory.load());
    private static WebDriver driverThread = null;
    private static BrowserMobProxyServer proxy;
    DriverService appiumService = null;
    private String testName = null;

    /**
     * Added performance capability
     *
     * @return capabilities
     */
    protected static DesiredCapabilities performance() {
        log.info("Make sure that Docker containers are up and running");
        proxy = new BrowserMobProxyServer();
        proxy.start();
        Proxy seleniumProxy = ClientUtil.createSeleniumProxy(proxy);
        try {
            String hostIp = Inet4Address.getLocalHost().getHostAddress();
            seleniumProxy.setHttpProxy(hostIp + ":" + proxy.getPort());
            seleniumProxy.setSslProxy(hostIp + ":" + proxy.getPort());
        } catch (Exception e) {
            log.error(e.getMessage());
        }
        proxy.enableHarCaptureTypes(CaptureType.REQUEST_CONTENT, CaptureType.RESPONSE_CONTENT);
        proxy.newHar("TestPerformance");
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability(CapabilityType.PROXY, seleniumProxy);
        return caps;
    }

    @Parameters({"type", "browser", "device", "grid", "perf"})
    @BeforeClass
    public void setup(String type, String browser, String device, String grid, String perf) {
        testName = this.getClass().getName().substring(24);
        switch (type) {
            case "web" -> initWebDriver(browser, grid, perf);
            case "mobile" -> initMobileDriver(device, grid);
            default -> log.info("select test type to proceed with one testing");
        }
    }

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public WebDriver getWebDriver() {
        return driverThread;
    }

    /**
     * Initialize web driver
     *
     * @param browser browser
     * @param grid    grid
     * @param perf    perf
     */
    private synchronized void initWebDriver(String browser, String grid, String perf) {
        try (DeviceFarmClient client = DeviceFarmClient.builder().region(Region.AP_SOUTHEAST_2).build()) {
            CreateTestGridUrlRequest request = CreateTestGridUrlRequest.builder()
                    .expiresInSeconds(300)
                    .projectArn("arn:aws:devicefarm:ap-southeast-2:111122223333:testgrid-project:1111111-2222-3333-4444-555555555")
                    .build();
            try {
                switch (grid) {
                    case "aws":
                        log.info("Make sure that the environment variables AWS_ACCESS_KEY and AWS_SECRET_KEY are configured in your testing environment.");
                        CreateTestGridUrlResponse response = client.createTestGridUrl(request);
                        driverThread = new RemoteWebDriver(new URL(response.url()), addCloudCapabilities(browser));
                        log.info("Grid client setup for AWS Device farm successful");
                        break;
                    case "docker":
                        log.info("Make sure that docker containers are up and running");
                        driverThread = new RemoteWebDriver(URI.create("http://localhost:4445/wd/hub").toURL(), getBrowserOptions(browser, perf));
                        log.info("Grid client setup for Docker containers successful");
                        break;
                    case "browserstack":
                        log.info("Make sure that browserstack configs provided");
                        driverThread = new RemoteWebDriver(createURL("browserstack"), addBrowserStackCapabilities(browser, testName));
                        log.info("Grid client setup for browserstack successful");
                        break;
                    case "lambda":
                        log.info("Make sure that lambda configs provided");
                        driverThread = new RemoteWebDriver(createURL("lambda"), addLambdaTestCapabilities(browser, testName));
                        log.info("Grid client setup for lambda successful");
                        break;
                    case "local":
                        switch (browser) {
                            case "firefox" -> {
                                driverThread = new FirefoxDriver(getFirefoxOptions());
                                log.info("Initiating firefox driver");
                            }
                            case "chrome" -> {
                                driverThread = new ChromeDriver(getChromeOptions(perf));
                                log.info("Initiating chrome driver");
                            }
                            case "edge" -> {
                                driverThread = new EdgeDriver(getEdgeOptions());
                                log.info("Initiating edge driver");
                            }
                            default -> log.info("Browser listed not supported");
                        }
                    default:
                        log.info("Running in local docker container");
                        break;
                }
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }

    /**
     * Initialize mobile driver
     *
     * @param device device
     */
    private synchronized void initMobileDriver(String device, String cloud) {
        try {
            switch (device) {
                case "NEXUS" -> {
                    log.info("Selected device is NEXUS");
                    caps.setCapability(MobileCapabilityType.UDID, "NEXUS");
                    caps.setCapability(MobileCapabilityType.DEVICE_NAME, "NEXUS");
                    androidCapabilities(caps);
                    cloudCapabilities(cloud, caps, "NEXUS");
                    driverThread = new RemoteWebDriver(createURL(cloud), caps);
                }
                case "PIXEL" -> {
                    log.info("Selected device is PIXEL");
                    caps.setCapability(MobileCapabilityType.UDID, "PIXEL");
                    caps.setCapability(MobileCapabilityType.DEVICE_NAME, "PIXEL");
                    androidCapabilities(caps);
                    cloudCapabilities(cloud, caps, "PIXEL");
                    driverThread = new RemoteWebDriver(createURL(cloud), caps);
                }
                case "samsung" -> {
                    log.info("Selected device is SAMSUNG");
                    cloudCapabilities(cloud, caps, "samsung");
                    driverThread = new RemoteWebDriver(createURL(cloud), caps);
                }
                case "iPhone14" -> {
                    log.info("Selected device is IPHONE");
                    cloudCapabilities(cloud, caps, "iPhone14");
                    driverThread = new RemoteWebDriver(createURL(cloud), caps);
                }
                case "IPHONE" -> {
                    log.info("Selected device is IPHONE");
                    caps.setCapability(MobileCapabilityType.UDID, "iphone");
                    caps.setCapability(MobileCapabilityType.DEVICE_NAME, "iphone");
                    iosCapabilities(caps);
                    cloudCapabilities(cloud, caps, "IPHONE");
                    driverThread = new RemoteWebDriver(createURL(cloud), caps);
                }
                case "EMULATOR" -> {
                    log.info("Selected device is EMULATOR");
                    appiumService = createAppiumService();
                    caps.setCapability(MobileCapabilityType.UDID, "NEXUS");
                    caps.setCapability(MobileCapabilityType.DEVICE_NAME, "NEXUS");
                    appiumService.start();
                    driverThread = new AndroidDriver(createURL(cloud), caps);
                }
                default -> log.info("Required device selection");
            }
        } catch (Exception ex) {
            log.error(ex.getMessage());
        }
    }

    @AfterClass
    public void tearDown() {
        try {
            Har har = proxy.getHar();
            FileOutputStream fos = new FileOutputStream("Reports\\performance\\" + testName + ".har");
            har.writeTo(fos);
            proxy.stop();
            log.info("Performance reports will be available at Report folder");
        } catch (Exception e) {
            log.info("Performance tests not included");
        } finally {
            if (driverThread != null) {
                driverThread.quit();
            } else {
                if (appiumService != null) {
                    appiumService.stop();
                    stopAppiumServer();
                }
            }
        }
    }
}

