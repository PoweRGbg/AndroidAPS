package info.nightscout.androidaps.plugins.iob.iobCobCalculator;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.collection.LongSparseArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.events.Event;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.aps.openAPSSMB.SMBDefaults;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.fitbit.FitbitControlPlugin;
import info.nightscout.androidaps.plugins.general.overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.general.overview.notifications.Notification;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.carbs.ActiveCarb;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.carbs.ActiveCarbFromDeviationHistory;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventAutosensCalculationFinished;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.events.EventIobCalculationProgress;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.DecimalFormatter;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.MidnightTime;
import info.nightscout.androidaps.utils.Profiler;
import info.nightscout.androidaps.utils.SP;
import info.nightscout.androidaps.utils.T;

import static info.nightscout.androidaps.utils.DateUtil.now;
import static java.util.Calendar.MINUTE;

/**
 * Created by mike on 23.01.2018.
 */

public class IobCobOref1Thread extends Thread {
    private static Logger log = LoggerFactory.getLogger(L.AUTOSENS);
    private final Event cause;

    private IobCobCalculatorPlugin iobCobCalculatorPlugin;
    private boolean bgDataReload;
    private boolean limitDataToOldestAvailable;
    private String from;
    private long end;

    private PowerManager.WakeLock mWakeLock;

    IobCobOref1Thread(IobCobCalculatorPlugin plugin, String from, long end, boolean bgDataReload, boolean limitDataToOldestAvailable, Event cause) {
        super();

        this.iobCobCalculatorPlugin = plugin;
        this.bgDataReload = bgDataReload;
        this.limitDataToOldestAvailable = limitDataToOldestAvailable;
        this.from = from;
        this.cause = cause;
        this.end = end;

        PowerManager powerManager = (PowerManager) MainApp.instance().getApplicationContext().getSystemService(Context.POWER_SERVICE);
        if (powerManager != null)
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, MainApp.gs(R.string.app_name) + ":iobCobThread");
    }

    @Override
    public final void run() {
        long start = DateUtil.now();
        if (mWakeLock != null)
            mWakeLock.acquire(T.mins(10).msecs());
        try {
            if (L.isEnabled(L.AUTOSENS))
                log.debug("AUTOSENSDATA thread started: " + from);
            if (ConfigBuilderPlugin.getPlugin() == null) {
                if (L.isEnabled(L.AUTOSENS))
                    log.debug("Aborting calculation thread (ConfigBuilder not ready): " + from);
                return; // app still initializing
            }
            if (!ProfileFunctions.getInstance().isProfileValid("IobCobThread")) {
                if (L.isEnabled(L.AUTOSENS))
                    log.debug("Aborting calculation thread (No profile): " + from);
                return; // app still initializing
            }
            //log.debug("Locking calculateSensitivityData");

            long oldestTimeWithData = iobCobCalculatorPlugin.calculateDetectionStart(end, limitDataToOldestAvailable);

            synchronized (iobCobCalculatorPlugin.getDataLock()) {
                if (bgDataReload) {
                    iobCobCalculatorPlugin.loadBgData(end);
                    iobCobCalculatorPlugin.createBucketedData();
                }
                List<BgReading> bucketed_data = iobCobCalculatorPlugin.getBucketedData();
                LongSparseArray<AutosensData> autosensDataTable = iobCobCalculatorPlugin.getAutosensDataTable();

                if (bucketed_data == null || bucketed_data.size() < 3) {
                    if (L.isEnabled(L.AUTOSENS))
                        log.debug("Aborting calculation thread (No bucketed data available): " + from);
                    return;
                }

                long prevDataTime = IobCobCalculatorPlugin.roundUpTime(bucketed_data.get(bucketed_data.size() - 3).date);
                if (L.isEnabled(L.AUTOSENS))
                    log.debug("Prev data time: " + new Date(prevDataTime).toLocaleString());
                AutosensData previous = autosensDataTable.get(prevDataTime);
                // start from oldest to be able sub cob
                for (int i = bucketed_data.size() - 4; i >= 0; i--) {
                    String progress = i + (MainApp.isDev() ? " (" + from + ")" : "");
                    RxBus.INSTANCE.send(new EventIobCalculationProgress(progress));

                    if (iobCobCalculatorPlugin.stopCalculationTrigger) {
                        iobCobCalculatorPlugin.stopCalculationTrigger = false;
                        if (L.isEnabled(L.AUTOSENS))
                            log.debug("Aborting calculation thread (trigger): " + from);
                        return;
                    }
                    // check if data already exists
                    long bgTime = bucketed_data.get(i).date;
                    bgTime = IobCobCalculatorPlugin.roundUpTime(bgTime);
                    if (bgTime > IobCobCalculatorPlugin.roundUpTime(now()))
                        continue;

                    AutosensData existing;
                    if ((existing = autosensDataTable.get(bgTime)) != null) {
                        previous = existing;
                        continue;
                    }

                    Profile profile = ProfileFunctions.getInstance().getProfile(bgTime);
                    if (profile == null) {
                        if (L.isEnabled(L.AUTOSENS))
                            log.debug("Aborting calculation thread (no profile): " + from);
                        return; // profile not set yet
                    }

                    if (L.isEnabled(L.AUTOSENS))
                        log.debug("Processing calculation thread: " + from + " (" + i + "/" + bucketed_data.size() + ")");

                    double sens = profile.getIsfMgdl(bgTime);

                    AutosensData autosensData = new AutosensData();
                    autosensData.time = bgTime;
                    if (previous != null)
                        autosensData.activeCarbsList = previous.cloneCarbsList();
                    else
                        autosensData.activeCarbsList = new ArrayList<>();

                    //console.error(bgTime , bucketed_data[i].glucose);
                    double bg;
                    double avgDelta;
                    double delta;
                    bg = bucketed_data.get(i).value;
                    if (bg < 39 || bucketed_data.get(i + 3).value < 39) {
                        log.error("! value < 39");
                        continue;
                    }
                    autosensData.bg = bg;
                    delta = (bg - bucketed_data.get(i + 1).value);
                    avgDelta = (bg - bucketed_data.get(i + 3).value) / 3;

                    IobTotal iob = iobCobCalculatorPlugin.calculateFromTreatmentsAndTemps(bgTime, profile);

                    double bgi = -iob.activity * sens * 5;
                    double deviation = delta - bgi;
                    double avgDeviation = Math.round((avgDelta - bgi) * 1000) / 1000d;

                    double slopeFromMaxDeviation = 0;
                    double slopeFromMinDeviation = 999;
                    double maxDeviation = 0;
                    double minDeviation = 999;

                    // https://github.com/openaps/oref0/blob/master/lib/determine-basal/cob-autosens.js#L169
                    if (i < bucketed_data.size() - 16) { // we need 1h of data to calculate minDeviationSlope
                        long hourago = bgTime + 10 * 1000 - 60 * 60 * 1000L;
                        AutosensData hourAgoData = iobCobCalculatorPlugin.getAutosensData(hourago);
                        if (hourAgoData != null) {
                            int initialIndex = autosensDataTable.indexOfKey(hourAgoData.time);
                            if (L.isEnabled(L.AUTOSENS))
                                log.debug(">>>>> bucketed_data.size()=" + bucketed_data.size() + " i=" + i + " hourAgoData=" + hourAgoData.toString());
                            int past = 1;
                            try {
                                for (; past < 12; past++) {
                                    AutosensData ad = autosensDataTable.valueAt(initialIndex + past);
                                    if (L.isEnabled(L.AUTOSENS)) {
                                        log.debug(">>>>> past=" + past + " ad=" + (ad != null ? ad.toString() : null));
                                        if (ad == null) {
                                            log.debug(autosensDataTable.toString());
                                            log.debug(bucketed_data.toString());
                                            log.debug(IobCobCalculatorPlugin.getPlugin().getBgReadings().toString());
                                            Notification notification = new Notification(Notification.SENDLOGFILES, MainApp.gs(R.string.sendlogfiles), Notification.LOW);
                                            RxBus.INSTANCE.send(new EventNewNotification(notification));
                                            SP.putBoolean("log_AUTOSENS", true);
                                            break;
                                        }
                                    }
                                    // let it here crash on NPE to get more data as i cannot reproduce this bug
                                    double deviationSlope = (ad.avgDeviation - avgDeviation) / (ad.time - bgTime) * 1000 * 60 * 5;
                                    if (ad.avgDeviation > maxDeviation) {
                                        slopeFromMaxDeviation = Math.min(0, deviationSlope);
                                        maxDeviation = ad.avgDeviation;
                                    }
                                    if (ad.avgDeviation < minDeviation) {
                                        slopeFromMinDeviation = Math.max(0, deviationSlope);
                                        minDeviation = ad.avgDeviation;
                                    }

                                    //if (Config.isEnabled(L.AUTOSENS))
                                    //    log.debug("Deviations: " + new Date(bgTime) + new Date(ad.time) + " avgDeviation=" + avgDeviation + " deviationSlope=" + deviationSlope + " slopeFromMaxDeviation=" + slopeFromMaxDeviation + " slopeFromMinDeviation=" + slopeFromMinDeviation);
                                }
                            } catch (Exception e) {
                                log.error("Unhandled exception", e);
                                FabricPrivacy.logException(e);
                                log.debug(autosensDataTable.toString());
                                log.debug(bucketed_data.toString());
                                log.debug(IobCobCalculatorPlugin.getPlugin().getBgReadings().toString());
                                Notification notification = new Notification(Notification.SENDLOGFILES, MainApp.gs(R.string.sendlogfiles), Notification.LOW);
                                RxBus.INSTANCE.send(new EventNewNotification(notification));
                                SP.putBoolean("log_AUTOSENS", true);
                                break;
                            }
                        } else {
                            if (L.isEnabled(L.AUTOSENS))
                                log.debug(">>>>> bucketed_data.size()=" + bucketed_data.size() + " i=" + i + " hourAgoData=" + "null");
                        }
                    }

                    List<Treatment> recentCarbTreatments = TreatmentsPlugin.getPlugin().getCarbTreatments5MinBackFromHistory(bgTime);
                    for (Treatment recentCarbTreatment : recentCarbTreatments) {
                        autosensData.carbsFromBolus += recentCarbTreatment.carbs;
                        autosensData.activeCarbsList.add(new ActiveCarbFromDeviationHistory(recentCarbTreatment));
                        autosensData.pastSensitivity += "[" + DecimalFormatter.to0Decimal(recentCarbTreatment.carbs) + "g]";
                    }

                    ArrayList<PluginBase> pluginsList = MainApp.getSpecificPluginsList(PluginType.GENERAL);
                    FitbitControlPlugin fitbitPlugin = null;
                    if (pluginsList != null) {
                        for (PluginBase p : pluginsList) {
                            if (p.getClass() == FitbitControlPlugin.class) {
                                fitbitPlugin = (FitbitControlPlugin)p;
                            }
                        }
                    }

                    autosensData.caloriesUsed = 0f;
                    /*
                    if (fitbitPlugin != null) {
                        autosensData.caloriesUsed = fitbitPlugin.getCaloriesAt(bgTime);
                    }
                     */

                    List<CareportalEvent> careportalHREvents = MainApp.getDbHelper().getCareportalEvents(CareportalEvent.HEARTRATE, bgTime - (30 * 60 * 1000), bgTime, false);
                    if (careportalHREvents.size() > 0) {
                        // Moving avg of up to the last 3 values (to smooth)
                        for(CareportalEvent hrEvent : careportalHREvents) {
                            if (hrEvent != null) {
                                autosensData.caloriesUsed += hrEvent.getCalories();
                            }
                        }
                        autosensData.caloriesUsed = autosensData.caloriesUsed / careportalHREvents.size();
                    }

                    // calories are assumed to decay to 0 over 1 hour
                    // need to add to devation for carb absobsion and remove from the prediction

                    // if we are absorbing carbs
                    if (previous != null && previous.cob > 0) {
/*
                        // calculate sum of min carb impact from all active treatments
                        autosensData.totalMinExpectedCarbsImpact = 0d;
                        for (int ii = 0; ii < autosensData.activeCarbsList.size(); ++ii) {
                            ActiveCarb c = autosensData.activeCarbsList.get(ii);
                            autosensData.totalMinExpectedCarbsImpact += c.min5minCarbImpact;
                        }
*/
                        /*
                        if (SensitivityAAPSPlugin.getPlugin().isEnabled(PluginType.SENSITIVITY) || SensitivityWeightedAveragePlugin.getPlugin().isEnabled(PluginType.SENSITIVITY)) {
                        } else {
                            //Oref sensitivity
                            totalMinCarbsImpact = SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, SMBDefaults.min_5m_carbimpact);
                        }
                        double ci = Math.max(deviation, totalMinCarbsImpact);
                        */

                        // Carbs can never makes us negative, so negative devations are not relevant
                        double ci = Math.max(0d, deviation);
                        if (ci < 0) { //autosensData.totalMinExpectedCarbsImpact
                            autosensData.failoverToMinAbsorbtionRate = true;
                        }

                        // TODO: remove this
                        //ci = Math.max(autosensData.totalMinExpectedCarbsImpact, ci);

                        // figure out how many carbs that represents
                        autosensData.absorbed = ci * profile.getIc(bgTime) / sens;
                        autosensData.discarded = 0; //Math.max(0,autosensData.totalMinExpectedCarbsImpact - ci) * profile.getIc(bgTime) / sens;
                        // and add that to the running total carbsAbsorbed
                        autosensData.cob = 0 ;
                        for (int ii = 0; ii < autosensData.activeCarbsList.size(); ++ii) {
                            ActiveCarb c = autosensData.activeCarbsList.get(ii);
                            autosensData.cob += c.getCarbsRemaining();
                        }
//                        autosensData.cob = Math.max(previous.cob - (autosensData.absorbed+autosensData.discarded), 0d);
                        autosensData.mealCarbs = previous.mealCarbs;
                        autosensData.substractAbosorbedCarbs();

                        // Only needed for the most recent hour of data (speeds up AAPS startup)
                        if (bgTime > System.currentTimeMillis() - 1000*60*60*3) {
                            autosensData.calculateDeviationsForErrors();
                        }

                        autosensData.usedMinCarbsImpact = 0d; // TODO: ???
                        autosensData.absorbing = previous.absorbing;
                        autosensData.mealStartCounter = previous.mealStartCounter;
                        autosensData.type = previous.type;
                        autosensData.uam = previous.uam;
                    }

                    /* Heart rate delta ideas:

                    (30 min exercise)
                    moderate exercise = drop 4.4(+-1.2) mmol/L drop (0 to 40% VO2max) (0 to 60% HR max)
                    high exercise = drop 2.9(+-0.8) mmol/L drop (40 to 80%? VO2max) (60% to 90% HR max)
                    very high = increase - underfined amount (80 to 100% VO2max) (90 to 100% HR max) continues for 2 hours, but recovery time 14 to 20 hours after causes low
                    https://www.ncbi.nlm.nih.gov/pubmed/15920041
                    https://care.diabetesjournals.org/content/28/6/1289.abstract

                    https://examinedexistence.com/whats-the-relationship-between-vo2max-and-heart-rate

                    https://www.ncsf.org/pdf/ceu/relationship_between_percent_hr_max_and_percent_vo2_max.pdf
                    lactate (acid?) level

                    60% VO2max = 75 max heart rate

                    estimated max heart rate = 217 - (0.85 * age) = 180 for hilary

                    140bpm = 60% VO2max

                    VO2max = 15 * (max / rest) = 35

                    115/77
                     */


                    autosensData.removeOldCarbs(bgTime);
                    autosensData.cob += autosensData.carbsFromBolus;
                    autosensData.mealCarbs += autosensData.carbsFromBolus;
                    autosensData.deviation = deviation;
                    autosensData.bgi = bgi;
                    autosensData.delta = delta;
                    autosensData.avgDelta = avgDelta;
                    autosensData.avgDeviation = avgDeviation;
                    autosensData.slopeFromMaxDeviation = slopeFromMaxDeviation;
                    autosensData.slopeFromMinDeviation = slopeFromMinDeviation;

/*
                    // Generate deviation predications for predicationSize*5 minutes
                    int predicationSize = 12;
                    if (autosensData.activeCarbsList.size() > 0) {
                        predicationSize = 12;
                    }

                    //if (bgTime == 1570806420000l) {
                        autosensData.generatePredicatedCarbs(predicationSize);
                    //}


                    //
                    // Get and evaluate old predictions
                    long evalStartTime = bgTime - ((predicationSize+1) * 5 * 60 * 1000);
                    AutosensData toEval = autosensDataTable.get(evalStartTime);
                    if (toEval != null && toEval.predictedDeviations.size() > 0) {
                        StringBuilder deviationErrors = new StringBuilder();
                        if (toEval.activeCarbsList.size() > 0) {
                            predicationSize = 12;
                        }
                        boolean abort = false;
                        double predicationScore = 0;
                        for (int j = 0; abort == false && j < toEval.predictedDeviations.size(); j++) {
                            AutosensData old = autosensDataTable.get(bgTime - ((predicationSize-j) * 5 * 60 * 1000));
                            if (old == null || old.mealCarbs != toEval.mealCarbs) {
                                abort = true;
                            } else {
                                double predicatedDeviation = toEval.predictedDeviations.get(j);
                                double actualDeviation = old.deviation;
//                                predicationScore += Math.pow(predicatedDeviation-actualDeviation, 2);
                                predicationScore += predicatedDeviation-actualDeviation;
                                deviationErrors.append(String.format("%.2f", predicatedDeviation-actualDeviation));
                                deviationErrors.append(String.format("[%.2f %.2f]", predicatedDeviation, actualDeviation));
                                deviationErrors.append(" ");
                            }
                        }
                        if (!abort) {
                            log.debug("Deviation Predication Error @ " + evalStartTime + " " + new Date(evalStartTime).toLocaleString() + " " +toEval.activeCarbsList.size()+ " = " + String.format("%.2f", (predicationScore / predicationSize))+" ("+deviationErrors.toString()+")");
                        }
                    }
                    */

                    // If mealCOB is zero but all deviations since hitting COB=0 are positive, exclude from autosens
                    if (autosensData.cob > 0 || autosensData.absorbing || autosensData.mealCarbs > 0) {
                        if (deviation > 0)
                            autosensData.absorbing = true;
                        else
                            autosensData.absorbing = false;
                        // stop excluding positive deviations as soon as mealCOB=0 if meal has been absorbing for >5h
                        if (autosensData.mealStartCounter > 60 && autosensData.cob < 0.5) {
                            autosensData.absorbing = false;
                        }
                        if (!autosensData.absorbing && autosensData.cob < 0.5) {
                            autosensData.mealCarbs = 0;
                        }
                        // check previous "type" value, and if it wasn't csf, set a mealAbsorption start flag
                        if (!autosensData.type.equals("csf")) {
//                                process.stderr.write("(");
                            autosensData.mealStartCounter = 0;
                        }
                        autosensData.mealStartCounter++;
                        autosensData.type = "csf";
                    } else {
                        // check previous "type" value, and if it was csf, set a mealAbsorption end flag
                        if (autosensData.type.equals("csf")) {
//                                process.stderr.write(")");
                        }

                        double currentBasal = profile.getBasal(bgTime);
                        // always exclude the first 45m after each carb entry
                        //if (iob.iob > currentBasal || uam ) {
                        if (iob.iob > 2 * currentBasal || autosensData.uam || autosensData.mealStartCounter < 9) {
                            autosensData.mealStartCounter++;
                            if (deviation > 0)
                                autosensData.uam = true;
                            else
                                autosensData.uam = false;
                            if (!autosensData.type.equals("uam")) {
//                                    process.stderr.write("u(");
                            }
                            autosensData.type = "uam";
                        } else {
                            if (autosensData.type.equals("uam")) {
//                                    process.stderr.write(")");
                            }
                            autosensData.type = "non-meal";
                        }
                    }

                    // Exclude meal-related deviations (carb absorption) from autosens
                    if (autosensData.type.equals("non-meal")) {
                        if (Math.abs(deviation) < Constants.DEVIATION_TO_BE_EQUAL) {
                            autosensData.pastSensitivity += "=";
                            autosensData.validDeviation = true;
                        } else if (deviation > 0) {
                            autosensData.pastSensitivity += "+";
                            autosensData.validDeviation = true;
                        } else {
                            autosensData.pastSensitivity += "-";
                            autosensData.validDeviation = true;
                        }
                    } else if (autosensData.type.equals("uam")) {
                        autosensData.pastSensitivity += "u";
                    } else {
                        autosensData.pastSensitivity += "x";
                    }
                    //log.debug("TIME: " + new Date(bgTime).toString() + " BG: " + bg + " SENS: " + sens + " DELTA: " + delta + " AVGDELTA: " + avgDelta + " IOB: " + iob.iob + " ACTIVITY: " + iob.activity + " BGI: " + bgi + " DEVIATION: " + deviation);

                    // add an extra negative deviation if a high temptarget is running and exercise mode is set
                    // TODO AS-FIX
                    if (false && SP.getBoolean(R.string.key_high_temptarget_raises_sensitivity, SMBDefaults.high_temptarget_raises_sensitivity)) {
                        TempTarget tempTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory(bgTime);
                        if (tempTarget != null && tempTarget.target() >= 100) {
                            autosensData.extraDeviation.add(-(tempTarget.target() - 100) / 20);
                        }
                    }

                    // add one neutral deviation every 2 hours to help decay over long exclusion periods
                    GregorianCalendar calendar = new GregorianCalendar();
                    calendar.setTimeInMillis(bgTime);
                    int min = calendar.get(MINUTE);
                    int hours = calendar.get(Calendar.HOUR_OF_DAY);
                    if (min >= 0 && min < 5 && hours % 2 == 0)
                        autosensData.extraDeviation.add(0d);

                    previous = autosensData;
                    if (bgTime < now())
                        autosensDataTable.put(bgTime, autosensData);
                    if (L.isEnabled(L.AUTOSENS))
                        log.debug("Running detectSensitivity from: " + DateUtil.dateAndTimeString(oldestTimeWithData) + " to: " + DateUtil.dateAndTimeString(bgTime) + " lastDataTime:" + iobCobCalculatorPlugin.lastDataTime());
                    AutosensResult sensitivity = iobCobCalculatorPlugin.detectSensitivityWithLock(oldestTimeWithData, bgTime);
                    if (L.isEnabled(L.AUTOSENS))
                        log.debug("Sensitivity result: " + sensitivity.toString());
                    autosensData.autosensResult = sensitivity;
//                    if (L.isEnabled(L.AUTOSENS))
                    log.debug(autosensData.toString());
                    for (int ii = 0; ii < autosensData.activeCarbsList.size(); ++ii) {
                        ActiveCarb c = autosensData.activeCarbsList.get(ii);
                        log.debug(c.toString());
                    }
                }
            }
            new Thread(() -> {
                SystemClock.sleep(1000);
                RxBus.INSTANCE.send(new EventAutosensCalculationFinished(cause));
            }).start();
        } finally {
            if (mWakeLock != null)
                mWakeLock.release();
            RxBus.INSTANCE.send(new EventIobCalculationProgress(""));
            if (L.isEnabled(L.AUTOSENS)) {
                log.debug("AUTOSENSDATA thread ended: " + from);
                log.debug("Midnights: " + MidnightTime.log());
            }
            Profiler.log(log, "IobCobOref1Thread", start);
        }
    }

}
