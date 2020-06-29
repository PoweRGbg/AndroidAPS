package info.nightscout.androidaps.plugins.general.fitbit;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import info.nightscout.androidaps.Config;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.Source;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.TreatmentsInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.aps.loop.APSResult;
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSDeviceStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.DecimalFormatter;
import info.nightscout.androidaps.utils.T;

public class FitbitWebService implements Runnable {
    private static Logger log = LoggerFactory.getLogger(L.FITBIT);

    private static final int MAX_RUNNING_THREADS = 15;

    private FitbitControlPlugin plugin;

    private final int listenPort;
    private final AtomicInteger thread_count = new AtomicInteger();

    private boolean isRunning;
    private ServerSocket mServerSocket;

    private Pattern paramMatcher = Pattern.compile("avg=(.0-9]+)");

    /**
     * WebServer constructor.
     */
    public FitbitWebService(int port, FitbitControlPlugin plugin)  {
        this.listenPort = port;
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            if (false) {
                final KeyStore keyStore = KeyStore.getInstance("BKS");
                keyStore.load(new BufferedInputStream(MainApp.sResources.openRawResource(R.raw.localhost_cert)), null);

                final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManagerFactory.init(keyStore, null);

                final TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);

                final SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);

                final SSLServerSocketFactory ssocketFactory = sslContext.getServerSocketFactory();

                mServerSocket = ssocketFactory.createServerSocket(listenPort, 1, InetAddress.getByName("127.0.0.1"));
            } else {
                mServerSocket = new ServerSocket(listenPort, 1, InetAddress.getByName("127.0.0.1"));
            }
            while (true) {
                final Socket socket = mServerSocket.accept();
                final int runningThreads = thread_count.get();
                if (runningThreads < MAX_RUNNING_THREADS) {
                    log.debug("Running threads: " + runningThreads);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            thread_count.incrementAndGet();
                            try {
                                handle(socket);
                                socket.close();
                            } catch (SocketException e) {
                                // ignore
                            } catch (IOException e) {
                                log.error("Web server thread error.", e);
                            } finally {
                                thread_count.decrementAndGet();
                            }
                        }
                    }).start();
                } else {
                    log.error("Web service jammed with too many connections > " + runningThreads);
                    socket.close();
                }

            }
        } catch (SocketException e) {
            // The server was stopped; ignore.
        } catch (UnknownHostException e) {
            log.error("Web server error.", e);
        } catch (IOException e) {
            log.error("Web server error.", e);
        } catch (CertificateException ex) {
            ex.printStackTrace();
        } catch (NoSuchAlgorithmException ex) {
            ex.printStackTrace();
        } catch (UnrecoverableKeyException ex) {
            ex.printStackTrace();
        } catch (KeyStoreException ex) {
            ex.printStackTrace();
        } catch (KeyManagementException ex) {
            ex.printStackTrace();
        }
    }

    private static Map<String, String> splitQuery(String query) throws UnsupportedEncodingException {
        Map<String, String> query_pairs = new LinkedHashMap<String, String>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            query_pairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
        }
        return query_pairs;
    }

    /**
     * Respond to a request from a client.
     *
     * @param socket The client socket.
     * @throws IOException
     */
    private void handle(Socket socket) throws IOException {
        // Might need a wake lock?

        BufferedReader reader = null;
        PrintStream output = null;
        try {
            socket.setSoTimeout((int) (T.secs(10).msecs()));

            String route = null;

            // Read HTTP headers and parse out the route.
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String line;

            Map<String, String> params = null;

            int lineCount = 0;
            while (!TextUtils.isEmpty(line = reader.readLine()) && lineCount < 50) {
                log.debug(line);

                if (line.startsWith("GET /")) {
                    int start = line.indexOf('/') + 1;
                    int end = line.indexOf(' ', start);
                    if (start < line.length()) {
                        route = line.substring(start, end);
                    }

                    int queryStringStart = line.indexOf("?");
                    int queryStringEnd = line.lastIndexOf(" ");
                    if (queryStringStart > 0) {
                        params = splitQuery(line.substring(queryStringStart + 1, queryStringEnd));
                    }

                } else if (line.startsWith(("api-secret"))) {
                    final String requestSecret[] = line.split(": ");
                    if (requestSecret.length < 2) continue;
                    break; // last and only header checked and will appear after GET request
                }
                lineCount++;
            }

            if (params != null) {
                // Legacy
                if (params.containsKey("avg")) {
                    params.put("avgBPM", params.get("avg"));
                }
                if (params.containsKey("avgBPM")) {
                    try {
                        Float bpm = Float.parseFloat(params.get("avgBPM"));
                        if (bpm > 30) {
                            long timestamp = System.currentTimeMillis();
                            JSONObject data = new JSONObject();
                            data.put("enteredBy", "Fitbit");
                            data.put("created_at", DateUtil.toISOString(timestamp));
                            data.put("eventType", CareportalEvent.HEARTRATE);
                            data.put("bpm", bpm);
                            if (params.containsKey("totalSteps")) {
                                data.put("steps", Float.parseFloat(params.get("totalSteps")));
                            }
                            if (params.containsKey("totalCalories")) {
                                data.put("calories", Float.parseFloat(params.get("totalCalories")));
                            }

                            // Make analysis easier by caputing deviation and carb statue in the same entry
                            AutosensData autosensData = IobCobCalculatorPlugin.getPlugin().getLastAutosensData("FitbitWebServicePlugin");
                            if (autosensData != null) {
                                data.put("deviation", autosensData.deviation);
                                data.put("cob", autosensData.cob);
                            }

                            Profile profile = ProfileFunctions.getInstance().getProfile(timestamp);
                            if (profile != null) {
                                data.put("sensitivity", profile.getIsfMgdl(timestamp));
                                IobTotal iob = IobCobCalculatorPlugin.getPlugin().calculateFromTreatmentsAndTemps(timestamp, profile);
                                if (iob != null) {
                                    data.put("insulinActivity", iob.activity);
                                    data.put("iob", iob.iob);
                                }
                            }

                            NSUpload.uploadCareportalEntryToNS(data);
                        }
/*
                        if (bpm > 100) {
                            TempTarget tempTarget = new TempTarget()
                                    .date(DateUtil.now())
                                    .duration(15)
                                    .reason("Heart rate of " + bpm)
                                    .source(Source.USER)
                                    .low(Profile.toMgdl(8, Constants.MMOL))
                                    .high(Profile.toMgdl(8, Constants.MMOL));
                            TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTarget);
                        } else if (bpm > 85) {
                            TempTarget tempTarget = new TempTarget()
                                    .date(DateUtil.now())
                                    .duration(15)
                                    .reason("Heart rate of " + bpm)
                                    .source(Source.USER)
                                    .low(Profile.toMgdl(7, Constants.MMOL))
                                    .high(Profile.toMgdl(7, Constants.MMOL));
                            TreatmentsPlugin.getPlugin().addToHistoryTempTarget(tempTarget);
                        }
 */
                    } catch (JSONException e) {
                        log.error("Unhandled exception", e);
                    }
                }
            }

            log.debug(route);

            // Output stream that we send the response to
            output = new PrintStream(socket.getOutputStream());

            writeServerError(output);

            String response = buildStatusString(null);

            // Send out the content.
            output.println("HTTP/1.0 200 OK");
            output.println("Access-Control-Allow-Origin: *");
            output.println("Content-Type: application/json");
            output.println("Content-Length: " + response.length());
            output.println();
            output.println(response);
            output.flush();

        } catch (SocketTimeoutException e) {
            log.error("Got socket timeout: " + e);
        } catch (NullPointerException e) {
            log.error("Got null pointer exception: " + e);
        } finally {
            if (output != null) {
                output.close();
            }
            if (reader != null) {
                reader.close();
            }
//            JoH.releaseWakeLock(wl);
        }
    }

    @NonNull
    private String buildStatusString(Profile profile) {
        JSONObject result = new JSONObject();

        try {
            long fromTime = DateUtil.now() - T.mins(45).msecs();

            JSONArray bgValues = new JSONArray();
            List<BgReading> bgData = MainApp.getDbHelper().getBgreadingsDataFromTime(fromTime, true);
            if (bgData.size() > 0) {
                result.put("firstBgTimestamp", bgData.get(0).date);
                result.put("lastBgTimestamp", bgData.get(bgData.size() - 1).date);
                for (int i = 0; i < bgData.size(); i++) {
                    bgValues.put(bgData.get(i).value);
                }
            }
            result.put("bg", bgValues);

            final LoopPlugin.LastRun finalLastRun = LoopPlugin.lastRun;
            if (finalLastRun != null) {
                APSResult apsResult = null;
                if (Config.APS)
                    apsResult = finalLastRun.constraintsProcessed;
                else
                    apsResult = NSDeviceStatus.getAPSResult();

                if (apsResult != null) {
                    JSONArray bgPredications = new JSONArray();
                    List<BgReading> bgPredicationData = apsResult.getPredictions();
                    for (int i = 0; i < bgPredicationData.size(); i++) {
                        if (bgPredicationData.get(i).isaCOBPrediction) {
                            bgPredications.put(bgPredicationData.get(i).value);
                        }
                    }
                    result.put("predications", bgPredications);
                }
            }

        } catch (JSONException e) {
            e.printStackTrace();
        }
        return result.toString();
/*
        JSONArray mBolusActivityCurve = new JSONArray();


        if (ConfigBuilderPlugin.getPlugin().getActivePump() == null)
            return "";



        LoopPlugin loopPlugin = LoopPlugin.getPlugin();

        if (!loopPlugin.isEnabled(PluginType.LOOP)) {
            status += MainApp.gs(R.string.disabledloop) + "\n";
            lastLoopStatus = false;
        } else if (loopPlugin.isEnabled(PluginType.LOOP)) {
            lastLoopStatus = true;
        }

        //Temp basal
        TreatmentsInterface treatmentsInterface = TreatmentsPlugin.getPlugin();

        TemporaryBasal activeTemp = treatmentsInterface.getTempBasalFromHistory(System.currentTimeMillis());
        if (activeTemp != null) {
            status += activeTemp.toStringShort() + " ";
        }

        //IOB
        treatmentsInterface.updateTotalIOBTreatments();
        IobTotal bolusIob = treatmentsInterface.getLastCalculationTreatments().round();
        treatmentsInterface.updateTotalIOBTempBasals();
        IobTotal basalIob = treatmentsInterface.getLastCalculationTempBasals().round();
        status += DecimalFormatter.to2Decimal(bolusIob.iob + basalIob.basaliob) + "U";


        if (mPrefs.getBoolean("xdripstatus_detailediob", true)) {
            status += "("
                    + DecimalFormatter.to2Decimal(bolusIob.iob) + "|"
                    + DecimalFormatter.to2Decimal(basalIob.basaliob) + ")";
        }

        if (!mPrefs.getBoolean("xdripstatus_showbgi", false)) {
            return status;
        }

        double bgi = -(bolusIob.activity + basalIob.activity) * 5 * profile.getIsf();

        status += " " + ((bgi >= 0) ? "+" : "") + DecimalFormatter.to2Decimal(bgi);
        status += " " + IobCobCalculatorPlugin.getPlugin().getCobInfo(false, "StatuslinePlugin").generateCOBString();

        return status;

 */
    }


    /**
     * Writes a server error response (HTTP/1.0 500) to the given output stream.
     *
     * @param output The output stream.
     */
    private void writeServerError(final PrintStream output) {
        output.println("HTTP/1.0 500 Internal Server Error");
        output.flush();
    }

}
