package com.rusefi;

import com.devexperts.logging.Logging;
import com.opensr5.ConfigurationImage;
import com.opensr5.ini.field.ScalarIniField;
import com.rusefi.binaryprotocol.BinaryProtocol;
import com.rusefi.config.generated.Fields;
import com.rusefi.io.ConnectionStateListener;
import com.rusefi.io.LinkManager;
import com.rusefi.io.tcp.BinaryProtocolServer;
import com.rusefi.io.tcp.TcpIoStream;
import com.rusefi.proxy.NetworkConnector;
import com.rusefi.proxy.NetworkConnectorContext;
import com.rusefi.proxy.client.LocalApplicationProxy;
import com.rusefi.proxy.client.LocalApplicationProxyContext;
import com.rusefi.proxy.client.UpdateType;
import com.rusefi.server.*;
import com.rusefi.tools.online.HttpUtil;
import org.apache.http.HttpResponse;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.devexperts.logging.Logging.getLogging;
import static com.rusefi.TestHelper.*;
import static com.rusefi.Timeouts.SECOND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FullServerTest {
    private static final Logging log = getLogging(FullServerTest.class);

    @Before
    public void setup() throws MalformedURLException {
        BackendTestHelper.commonServerTest();
    }

    @Test
    public void testRelayWorkflow() throws InterruptedException, IOException {
        ScalarIniField iniField = TestHelper.createIniField(Fields.CYLINDERSCOUNT);
        int value = 241;
        int userId = 7;


        LocalApplicationProxyContext localApplicationProxyContext = new LocalApplicationProxyContext() {
            @Override
            public String executeGet(String url) throws IOException {
                return HttpUtil.executeGet(url);
            }

            @Override
            public int serverPortForRemoteApplications() {
                return 7003;
            }

            @Override
            public int authenticatorPort() {
                return 7004;
            }
        };

        CountDownLatch controllerRegistered = new CountDownLatch(1);
        CountDownLatch applicationClosed = new CountDownLatch(1);

        UserDetailsResolver userDetailsResolver = authToken -> new UserDetails(authToken.substring(0, 5), userId);
        int httpPort = 8103;
        int applicationTimeout = 7 * SECOND;
        try (Backend backend = new Backend(userDetailsResolver, httpPort, applicationTimeout) {
            @Override
            public void register(ControllerConnectionState controllerConnectionState) {
                super.register(controllerConnectionState);
                controllerRegistered.countDown();
            }

            @Override
            protected void close(ApplicationConnectionState applicationConnectionState) {
                super.close(applicationConnectionState);
                applicationClosed.countDown();
            }
        }; LinkManager clientManager = new LinkManager().setNeedPullData(false);
             NetworkConnector networkConnector = new NetworkConnector()) {
            int serverPortForControllers = 7001;


            // first start backend server
            BackendTestHelper.runControllerConnectorBlocking(backend, serverPortForControllers);
            BackendTestHelper.runApplicationConnectorBlocking(backend, localApplicationProxyContext.serverPortForRemoteApplications());

            // create virtual controller to which "rusEFI network connector" connects to
            int controllerPort = 7002;
            ConfigurationImage controllerImage = prepareImage(value, createIniField(Fields.CYLINDERSCOUNT));
            TestHelper.createVirtualController(controllerPort, controllerImage, new BinaryProtocolServer.Context());

            CountDownLatch softwareUpdateRequest = new CountDownLatch(1);

            NetworkConnectorContext networkConnectorContext = new NetworkConnectorContext() {
                @Override
                public int serverPortForControllers() {
                    return serverPortForControllers;
                }

                @Override
                public void onConnectorSoftwareUpdateToLatestRequest() {
                    softwareUpdateRequest.countDown();
                }
            };

            // start "rusEFI network connector" to connect controller with backend since in real life controller has only local serial port it does not have network
            NetworkConnector.NetworkConnectorResult networkConnectorResult = networkConnector.start(NetworkConnector.Implementation.Unknown,
                    TestHelper.TEST_TOKEN_1, TestHelper.LOCALHOST + ":" + controllerPort, networkConnectorContext, NetworkConnector.ReconnectListener.VOID);
            ControllerInfo controllerInfo = networkConnectorResult.getControllerInfo();

            TestHelper.assertLatch("controllerRegistered", controllerRegistered);

            SessionDetails authenticatorSessionDetails = new SessionDetails(NetworkConnector.Implementation.Unknown, controllerInfo, TEST_TOKEN_3, networkConnectorResult.getOneTimeToken(), rusEFIVersion.CONSOLE_VERSION);
            ApplicationRequest applicationRequest = new ApplicationRequest(authenticatorSessionDetails, userDetailsResolver.apply(TestHelper.TEST_TOKEN_1));

            HttpResponse response = LocalApplicationProxy.requestSoftwareUpdate(httpPort, applicationRequest, UpdateType.CONTROLLER);
            log.info("requestSoftwareUpdate response: " + response.toString());
            assertLatch("update requested", softwareUpdateRequest);

            // start authenticator
            LocalApplicationProxy.startAndRun(localApplicationProxyContext, applicationRequest, httpPort,
                    TcpIoStream.DisconnectListener.VOID,
                    LocalApplicationProxy.ConnectionListener.VOID);


            CountDownLatch connectionEstablishedCountDownLatch = new CountDownLatch(1);

            // connect to proxy and read virtual controller through it
            clientManager.startAndConnect(TestHelper.LOCALHOST + ":" + localApplicationProxyContext.authenticatorPort(), new ConnectionStateListener() {
                @Override
                public void onConnectionEstablished() {
                    connectionEstablishedCountDownLatch.countDown();
                }

                @Override
                public void onConnectionFailed() {
                    System.out.println("Failed");
                }
            });
            assertLatch("Proxied ECU Connection established", connectionEstablishedCountDownLatch);

            BinaryProtocol clientStreamState = clientManager.getCurrentStreamState();
            Objects.requireNonNull(clientStreamState, "clientStreamState");
            ConfigurationImage clientImage = clientStreamState.getControllerConfiguration();
            String clientValue = iniField.getValue(clientImage);
            assertEquals(Double.toString(value), clientValue);

            assertEquals(1, backend.getApplications().size());
            assertEquals(1, applicationClosed.getCount());

            // now let's test that application connector would be terminated by server due to inactivity
            log.info("**************************************");
            log.info("Sleeping twice the application timeout");
            log.info("**************************************");
            assertLatch("applicationClosed", applicationClosed, 3 * applicationTimeout);

            assertEquals("applications size", 0, backend.getApplications().size());
        }
    }
}
