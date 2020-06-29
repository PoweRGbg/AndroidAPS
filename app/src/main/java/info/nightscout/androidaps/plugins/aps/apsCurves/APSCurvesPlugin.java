package info.nightscout.androidaps.plugins.aps.apsCurves;

import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.interfaces.APSInterface;
import info.nightscout.androidaps.interfaces.Constraint;
import info.nightscout.androidaps.interfaces.ConstraintsInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.interfaces.PumpInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.aps.loop.APSResult;
import info.nightscout.androidaps.plugins.aps.loop.ScriptReader;
import info.nightscout.androidaps.plugins.aps.openAPSMA.events.EventOpenAPSUpdateGui;
import info.nightscout.androidaps.plugins.aps.openAPSMA.events.EventOpenAPSUpdateResultGui;
import info.nightscout.androidaps.plugins.aps.openAPSSMB.OpenAPSSMBFragment;
import info.nightscout.androidaps.plugins.aps.openAPSSMB.SMBDefaults;
import info.nightscout.androidaps.plugins.bus.RxBus;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensResult;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.FabricPrivacy;
import info.nightscout.androidaps.utils.HardLimits;
import info.nightscout.androidaps.utils.Profiler;
import info.nightscout.androidaps.utils.Round;
import info.nightscout.androidaps.utils.ToastUtils;

/**
 * Created by mike on 05.08.2016.
 */
public class APSCurvesPlugin extends PluginBase implements APSInterface, ConstraintsInterface {
    private static Logger log = LoggerFactory.getLogger(L.APS);

    private static APSCurvesPlugin openAPSSMBPlugin;

    public static APSCurvesPlugin getPlugin() {
        if (openAPSSMBPlugin == null) {
            openAPSSMBPlugin = new APSCurvesPlugin();
        }
        return openAPSSMBPlugin;
    }

    // last values
    DetermineBasalAdapterCurvesJS lastDetermineBasalAdapterCurvesJS = null;
    long lastAPSRun = 0;
    DetermineBasalResultCurves lastAPSResult = null;
    AutosensResult lastAutosensResult = null;

    private APSCurvesPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.APS)
                .fragmentClass(APSCurvesFragment.class.getName())
                .pluginName(R.string.apscurves)
                .shortName(R.string.curves_shortname)
                .preferencesId(R.xml.pref_apscurves)
                .description(R.string.description_curves)
        );
    }

    @Override
    public boolean specialEnableCondition() {
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
        return pump == null || pump.getPumpDescription().isTempBasalCapable;
    }

    @Override
    public boolean specialShowInListCondition() {
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();
        return pump == null || pump.getPumpDescription().isTempBasalCapable;
    }

    @Override
    public APSResult getLastAPSResult() {
        return lastAPSResult;
    }

    @Override
    public long getLastAPSRun() {
        return lastAPSRun;
    }

    @Override
    public void invoke(String initiator, boolean tempBasalFallback) {
        if (L.isEnabled(L.APS))
            log.debug("invoke from " + initiator + " tempBasalFallback: " + tempBasalFallback);
        lastAPSResult = null;
        DetermineBasalAdapterCurvesJS determineBasalAdapterSMBJS;
        determineBasalAdapterSMBJS = new DetermineBasalAdapterCurvesJS(new ScriptReader(MainApp.instance().getBaseContext()));

        GlucoseStatus glucoseStatus = GlucoseStatus.getGlucoseStatusData();
        Profile profile = ProfileFunctions.getInstance().getProfile();
        PumpInterface pump = ConfigBuilderPlugin.getPlugin().getActivePump();

        if (profile == null) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.noprofileselected)));
            if (L.isEnabled(L.APS))
                log.debug(MainApp.gs(R.string.noprofileselected));
            return;
        }

        if (pump == null) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.nopumpselected)));
            if (L.isEnabled(L.APS))
                log.debug(MainApp.gs(R.string.nopumpselected));
            return;
        }

        if (!isEnabled(PluginType.APS)) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openapsma_disabled)));
            if (L.isEnabled(L.APS))
                log.debug(MainApp.gs(R.string.openapsma_disabled));
            return;
        }

        if (glucoseStatus == null) {
            RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openapsma_noglucosedata)));
            if (L.isEnabled(L.APS))
                log.debug(MainApp.gs(R.string.openapsma_noglucosedata));
            return;
        }

        Constraint<Double> inputConstraints = new Constraint<>(0d); // fake. only for collecting all results

        Constraint<Double> maxBasalConstraint = MainApp.getConstraintChecker().getMaxBasalAllowed(profile);
        inputConstraints.copyReasons(maxBasalConstraint);
        double maxBasal = maxBasalConstraint.value();
        double minBg = profile.getTargetLowMgdl();
        double maxBg = profile.getTargetHighMgdl();
        double targetBg = profile.getTargetMgdl();

        minBg = Round.roundTo(minBg, 0.1d);
        maxBg = Round.roundTo(maxBg, 0.1d);

        long start = System.currentTimeMillis();
        long startPart = System.currentTimeMillis();

        MealData mealData = TreatmentsPlugin.getPlugin().getMealData();
        if (L.isEnabled(L.APS))
            Profiler.log(log, "getMealData()", startPart);

        Constraint<Double> maxIOBAllowedConstraint = MainApp.getConstraintChecker().getMaxIOBAllowed();
        inputConstraints.copyReasons(maxIOBAllowedConstraint);
        double maxIob = maxIOBAllowedConstraint.value();

        minBg = verifyHardLimits(minBg, "minBg", HardLimits.VERY_HARD_LIMIT_MIN_BG[0], HardLimits.VERY_HARD_LIMIT_MIN_BG[1]);
        maxBg = verifyHardLimits(maxBg, "maxBg", HardLimits.VERY_HARD_LIMIT_MAX_BG[0], HardLimits.VERY_HARD_LIMIT_MAX_BG[1]);
        targetBg = verifyHardLimits(targetBg, "targetBg", HardLimits.VERY_HARD_LIMIT_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TARGET_BG[1]);


        boolean isTempTarget = false;
        TempTarget tempTarget = TreatmentsPlugin.getPlugin().getTempTargetFromHistory(System.currentTimeMillis());
        if (tempTarget != null) {
            isTempTarget = true;
            minBg = verifyHardLimits(tempTarget.low, "minBg", HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_MIN_BG[1]);
            maxBg = verifyHardLimits(tempTarget.high, "maxBg", HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_MAX_BG[1]);
            targetBg = verifyHardLimits(tempTarget.target(), "targetBg", HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[0], HardLimits.VERY_HARD_LIMIT_TEMP_TARGET_BG[1]);
        }

        if (!checkOnlyHardLimits(profile.getDia(), "dia", HardLimits.MINDIA, HardLimits.MAXDIA))
            return;
        if (!checkOnlyHardLimits(profile.getIcTimeFromMidnight(Profile.secondsFromMidnight()), "carbratio", HardLimits.MINIC, HardLimits.MAXIC))
            return;
        if (!checkOnlyHardLimits(profile.getIsfMgdl(), "sens", HardLimits.MINISF, HardLimits.MAXISF))
            return;
        if (!checkOnlyHardLimits(profile.getMaxDailyBasal(), "max_daily_basal", 0.02, HardLimits.maxBasal()))
            return;
        if (!checkOnlyHardLimits(pump.getBaseBasalRate(), "current_basal", 0.01, HardLimits.maxBasal()))
            return;

        startPart = System.currentTimeMillis();
        List<Double> predicatedCarbDeviations = null;
        if (MainApp.getConstraintChecker().isAutosensModeEnabled().value()) {
            AutosensData autosensData = IobCobCalculatorPlugin.getPlugin().getLastAutosensDataSynchronized("OpenAPSPlugin");
            if (autosensData == null) {
                RxBus.INSTANCE.send(new EventOpenAPSUpdateResultGui(MainApp.gs(R.string.openaps_noasdata)));
                return;
            }
            lastAutosensResult = autosensData.autosensResult;
/*
            float defaultCals = 5f;
            if (targetBg > 6.5*18) defaultCals = 7f;
            if (targetBg > 7.5*18) defaultCals = 18f;
*/
            predicatedCarbDeviations = autosensData.getPredicatedDeviations(49);
        } else {
            lastAutosensResult = new AutosensResult();
            lastAutosensResult.sensResult = "autosens disabled";
        }

        IobTotal[] iobArray = IobCobCalculatorPlugin.getPlugin().calculateIobArrayForSMB(lastAutosensResult, SMBDefaults.exercise_mode, SMBDefaults.half_basal_exercise_target, isTempTarget);
        if (L.isEnabled(L.APS))
            Profiler.log(log, "calculateIobArrayInDia()", startPart);

        long now = DateUtil.now();

        startPart = System.currentTimeMillis();
        Constraint<Boolean> smbAllowed = new Constraint<>(!tempBasalFallback);
        MainApp.getConstraintChecker().isSMBModeEnabled(smbAllowed);
        inputConstraints.copyReasons(smbAllowed);

        Constraint<Boolean> advancedFiltering = new Constraint<>(!tempBasalFallback);
        MainApp.getConstraintChecker().isAdvancedFilteringEnabled(advancedFiltering);
        inputConstraints.copyReasons(advancedFiltering);

        Constraint<Boolean> uam = new Constraint<>(true);
        MainApp.getConstraintChecker().isUAMEnabled(uam);
        inputConstraints.copyReasons(uam);

        if (L.isEnabled(L.APS))
            Profiler.log(log, "detectSensitivityandCarbAbsorption()", startPart);
        if (L.isEnabled(L.APS))
            Profiler.log(log, "SMB data gathering", start);

        start = System.currentTimeMillis();
        try {
            determineBasalAdapterSMBJS.setData(profile, maxIob, maxBasal, minBg, maxBg, targetBg, ConfigBuilderPlugin.getPlugin().getActivePump().getBaseBasalRate(), iobArray, glucoseStatus, mealData,
                    lastAutosensResult.ratio, //autosensDataRatio
                    predicatedCarbDeviations,
                    isTempTarget,
                    smbAllowed.value(),
                    uam.value(),
                    advancedFiltering.value()
            );
        } catch (JSONException e) {
            FabricPrivacy.logException(e);
            return;
        }

        DetermineBasalResultCurves determineBasalResultSMB = determineBasalAdapterSMBJS.invoke();
        if (L.isEnabled(L.APS))
            Profiler.log(log, "SMB calculation", start);
        if (determineBasalResultSMB == null) {
            if (L.isEnabled(L.APS))
                log.error("SMB calculation returned null");
            lastDetermineBasalAdapterCurvesJS = null;
            lastAPSResult = null;
            lastAPSRun = 0;
        } else {
            // TODO still needed with oref1?
            // Fix bug determine basal
            if (determineBasalResultSMB.rate == 0d && determineBasalResultSMB.duration == 0 && !TreatmentsPlugin.getPlugin().isTempBasalInProgress())
                determineBasalResultSMB.tempBasalRequested = false;

            determineBasalResultSMB.iob = iobArray[0];

            try {
                determineBasalResultSMB.json.put("timestamp", DateUtil.toISOString(now));
            } catch (JSONException e) {
                log.error("Unhandled exception", e);
            }

            determineBasalResultSMB.inputConstraints = inputConstraints;

            lastDetermineBasalAdapterCurvesJS = determineBasalAdapterSMBJS;
            lastAPSResult = determineBasalResultSMB;
            lastAPSRun = now;
        }
        RxBus.INSTANCE.send(new EventOpenAPSUpdateGui());
    }

    // safety checks
    private static boolean checkOnlyHardLimits(Double value, String valueName, double lowLimit, double highLimit) {
        return value.equals(verifyHardLimits(value, valueName, lowLimit, highLimit));
    }

    private static Double verifyHardLimits(Double value, String valueName, double lowLimit, double highLimit) {
        Double newvalue = value;
        if (newvalue < lowLimit || newvalue > highLimit) {
            newvalue = Math.max(newvalue, lowLimit);
            newvalue = Math.min(newvalue, highLimit);
            String msg = String.format(MainApp.gs(R.string.valueoutofrange), valueName);
            msg += ".\n";
            msg += String.format(MainApp.gs(R.string.valuelimitedto), value, newvalue);
            log.error(msg);
            NSUpload.uploadError(msg);
            ToastUtils.showToastInUiThread(MainApp.instance().getApplicationContext(), msg, R.raw.error);
        }
        return newvalue;
    }

    public Constraint<Boolean> isSuperBolusEnabled(Constraint<Boolean> value) {
        value.set(false);
        return value;
    }

}
