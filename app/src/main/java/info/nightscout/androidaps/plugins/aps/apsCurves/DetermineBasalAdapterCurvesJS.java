package info.nightscout.androidaps.plugins.aps.apsCurves;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.annotation.Nullable;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Iob;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.data.MealData;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.TemporaryBasal;
import info.nightscout.androidaps.interfaces.InsulinInterface;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.aps.loop.ScriptReader;
import info.nightscout.androidaps.plugins.aps.openAPSMA.LoggerCallback;
import info.nightscout.androidaps.plugins.aps.openAPSSMB.SMBDefaults;
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.AutosensData;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.SP;
import info.nightscout.androidaps.utils.SafeParse;

public class DetermineBasalAdapterCurvesJS {
    private static Logger log = LoggerFactory.getLogger(L.APS);


    private ScriptReader mScriptReader;
    private JSONObject mProfile;
    private JSONObject mGlucoseStatus;
    private JSONArray mIobData;
    private JSONObject mMealData;
    private JSONObject mCurrentTemp;
    private JSONObject mAutosensData = null;
    private boolean mMicrobolusAllowed;
    private boolean mSMBAlwaysAllowed;

    private String storedCurrentTemp = null;
    private String storedIobData = null;

    private String storedGlucoseStatus = null;
    private String storedProfile = null;
    private String storedMeal_data = null;
    private String storedAutosens_data = null;
    private String storedMicroBolusAllowed = null;
    private String storedSMBAlwaysAllowed = null;

    private String scriptDebug = "";

    /**
     * Main code
     */

    DetermineBasalAdapterCurvesJS(ScriptReader scriptReader) {
        mScriptReader = scriptReader;
    }


    @Nullable
    public DetermineBasalResultCurves invoke() {


        if (L.isEnabled(L.APS)) {
            log.debug(">>> Invoking detemine_basal <<<");
            log.debug("Glucose status: " + (storedGlucoseStatus = mGlucoseStatus.toString()));
            log.debug("IOB data:       " + (storedIobData = mIobData.toString()));
            log.debug("Current temp:   " + (storedCurrentTemp = mCurrentTemp.toString()));
            log.debug("Profile:        " + (storedProfile = mProfile.toString()));
            log.debug("Meal data:      " + (storedMeal_data = mMealData.toString()));
            if (mAutosensData != null)
                log.debug("Autosens data:  " + (storedAutosens_data = mAutosensData.toString()));
            else
                log.debug("Autosens data:  " + (storedAutosens_data = "undefined"));
            log.debug("Reservoir data: " + "undefined");
            log.debug("MicroBolusAllowed:  " + (storedMicroBolusAllowed = "" + mMicrobolusAllowed));
            log.debug("SMBAlwaysAllowed:  " + (storedSMBAlwaysAllowed = "" + mSMBAlwaysAllowed));
        }

        DetermineBasalResultCurves DetermineBasalResultCurves = null;

        Context rhino = Context.enter();
        Scriptable scope = rhino.initStandardObjects();
        // Turn off optimization to make Rhino Android compatible
        rhino.setOptimizationLevel(-1);

        try {

            //register logger callback for console.log and console.error
            ScriptableObject.defineClass(scope, LoggerCallback.class);
            Scriptable myLogger = rhino.newObject(scope, "LoggerCallback", null);
            scope.put("console2", scope, myLogger);
            rhino.evaluateString(scope, readFile("OpenAPSAMA/loggerhelper.js"), "JavaScript", 0, null);

            //set module parent
            rhino.evaluateString(scope, "var module = {\"parent\":Boolean(1)};", "JavaScript", 0, null);
            rhino.evaluateString(scope, "var round_basal = function round_basal(basal, profile) { return basal; };", "JavaScript", 0, null);
            rhino.evaluateString(scope, "require = function() {return round_basal;};", "JavaScript", 0, null);

            //generate functions "determine_basal" and "setTempBasal"
            rhino.evaluateString(scope, readFile("APSCurves/determine-basal.js"), "JavaScript", 0, null);
            rhino.evaluateString(scope, readFile("OpenAPSSMB/basal-set-temp.js"), "setTempBasal.js", 0, null);
            Object determineBasalObj = scope.get("determine_basal", scope);
            Object setTempBasalFunctionsObj = scope.get("tempBasalFunctions", scope);

            //call determine-basal
            if (determineBasalObj instanceof Function && setTempBasalFunctionsObj instanceof NativeObject) {
                Function determineBasalJS = (Function) determineBasalObj;

                //prepare parameters
                Object[] params = new Object[]{
                        makeParam(mGlucoseStatus, rhino, scope),
                        makeParam(mCurrentTemp, rhino, scope),
                        makeParamArray(mIobData, rhino, scope),
                        makeParam(mProfile, rhino, scope),
                        makeParam(mAutosensData, rhino, scope),
                        makeParam(mMealData, rhino, scope),
                        setTempBasalFunctionsObj,
                        new Boolean(mMicrobolusAllowed),
                        makeParam(null, rhino, scope) // reservoir data as undefined
                };


                NativeObject jsResult = (NativeObject) determineBasalJS.call(rhino, scope, scope, params);
                scriptDebug = LoggerCallback.getScriptDebug();

                // Parse the jsResult object to a JSON-String
                String result = NativeJSON.stringify(rhino, scope, jsResult, null, null).toString();
                if (L.isEnabled(L.APS))
                    log.debug("Result: " + result);
                try {
                    DetermineBasalResultCurves = new DetermineBasalResultCurves(new JSONObject(result));
                } catch (JSONException e) {
                    log.error("Unhandled exception", e);
                }
            } else {
                log.error("Problem loading JS Functions");
            }
        } catch (IOException e) {
            log.error("IOException");
        } catch (RhinoException e) {
            log.error("RhinoException: (" + e.lineNumber() + "," + e.columnNumber() + ") " + e.toString());
        } catch (IllegalAccessException | InstantiationException | InvocationTargetException e) {
            log.error(e.toString());
        } finally {
            Context.exit();
        }

        storedGlucoseStatus = mGlucoseStatus.toString();
        storedIobData = mIobData.toString();
        storedCurrentTemp = mCurrentTemp.toString();
        storedProfile = mProfile.toString();
        storedMeal_data = mMealData.toString();

        return DetermineBasalResultCurves;

    }

    String getGlucoseStatusParam() {
        return storedGlucoseStatus;
    }

    String getCurrentTempParam() {
        return storedCurrentTemp;
    }

    String getIobDataParam() {
        return storedIobData;
    }

    String getProfileParam() {
        return storedProfile;
    }

    String getMealDataParam() {
        return storedMeal_data;
    }

    String getAutosensDataParam() {
        return storedAutosens_data;
    }

    String getMicroBolusAllowedParam() {
        return storedMicroBolusAllowed;
    }

    String getScriptDebug() {
        return scriptDebug;
    }

    public void setData(Profile profile,
                        double maxIob,
                        double maxBasal,
                        double minBg,
                        double maxBg,
                        double targetBg,
                        double basalrate,
                        IobTotal[] iobArray,
                        GlucoseStatus glucoseStatus,
                        MealData mealData,
                        double autosensDataRatio,
                        List<Double> predicatedCarbDeviations,
                        boolean tempTargetSet,
                        boolean microBolusAllowed,
                        boolean uamAllowed,
                        boolean advancedFiltering
    ) throws JSONException {

        mProfile = new JSONObject();

        mProfile.put("max_iob", maxIob);
        //mProfile.put("dia", profile.getDia());
        mProfile.put("type", "current");
        mProfile.put("max_daily_basal", profile.getMaxDailyBasal());
        mProfile.put("max_basal", maxBasal);
        mProfile.put("min_bg", minBg);
        mProfile.put("max_bg", maxBg);
        mProfile.put("target_bg", targetBg);
        mProfile.put("min_threshold", 80);
        mProfile.put("carb_ratio", profile.getIc());
        mProfile.put("sens", profile.getIsfMgdl());
        mProfile.put("max_daily_safety_multiplier", SP.getInt(R.string.key_openapsama_max_daily_safety_multiplier, 3));
        mProfile.put("current_basal_safety_multiplier", SP.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4d));

        // TODO AS-FIX
        // mProfile.put("high_temptarget_raises_sensitivity", SP.getBoolean(R.string.key_high_temptarget_raises_sensitivity, SMBDefaults.high_temptarget_raises_sensitivity));
        mProfile.put("high_temptarget_raises_sensitivity", false);
        //mProfile.put("low_temptarget_lowers_sensitivity", SP.getBoolean(R.string.key_low_temptarget_lowers_sensitivity, SMBDefaults.low_temptarget_lowers_sensitivity));
        mProfile.put("low_temptarget_lowers_sensitivity", false);


        mProfile.put("sensitivity_raises_target", SMBDefaults.sensitivity_raises_target);
        mProfile.put("resistance_lowers_target", SMBDefaults.resistance_lowers_target);
        mProfile.put("adv_target_adjustments", SMBDefaults.adv_target_adjustments);
        mProfile.put("exercise_mode", SMBDefaults.exercise_mode);
        mProfile.put("half_basal_exercise_target", SMBDefaults.half_basal_exercise_target);
        mProfile.put("maxCOB", SMBDefaults.maxCOB);
        mProfile.put("skip_neutral_temps", SMBDefaults.skip_neutral_temps);
        // min_5m_carbimpact is not used within SMB determinebasal
        //if (mealData.usedMinCarbsImpact > 0) {
        //    mProfile.put("min_5m_carbimpact", mealData.usedMinCarbsImpact);
        //} else {
        //    mProfile.put("min_5m_carbimpact", SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, SMBDefaults.min_5m_carbimpact));
        //}
        mProfile.put("remainingCarbsCap", SMBDefaults.remainingCarbsCap);
        mProfile.put("enableUAM", uamAllowed);
        mProfile.put("A52_risk_enable", SMBDefaults.A52_risk_enable);

        boolean smbEnabled = SP.getBoolean(MainApp.gs(R.string.key_use_smb), false);
        mProfile.put("enableSMB_with_COB", smbEnabled && SP.getBoolean(R.string.key_enableSMB_with_COB, false));
        mProfile.put("enableSMB_with_temptarget", smbEnabled && SP.getBoolean(R.string.key_enableSMB_with_temptarget, false));
        mProfile.put("allowSMB_with_high_temptarget", smbEnabled && SP.getBoolean(R.string.key_allowSMB_with_high_temptarget, false));
        mProfile.put("enableSMB_always", smbEnabled && SP.getBoolean(R.string.key_enableSMB_always, false) && advancedFiltering);
        mProfile.put("enableSMB_after_carbs", smbEnabled && SP.getBoolean(R.string.key_enableSMB_after_carbs, false) && advancedFiltering);
        mProfile.put("maxSMBBasalMinutes", SP.getInt(R.string.key_smbmaxminutes, SMBDefaults.maxSMBBasalMinutes));
        mProfile.put("minSMBBasalPct", SP.getInt(R.string.key_apscurves_smb_min_basal, 30));
        if (mealData.mealCOB < 1) {
            mProfile.put("minSMBBasalPct", SP.getInt(R.string.key_apscurves_smb_min_basal_uam, 30));
        }

        mProfile.put("carbsReqThreshold", SMBDefaults.carbsReqThreshold);

        mProfile.put("current_basal", basalrate);
        mProfile.put("temptargetSet", tempTargetSet);
        mProfile.put("autosens_max", SafeParse.stringToDouble(SP.getString(R.string.key_openapsama_autosens_max, "1.2")));

        if (ProfileFunctions.getSystemUnits().equals(Constants.MMOL)) {
            mProfile.put("out_units", "mmol/L");
        }

        long now = System.currentTimeMillis();

        // Extracts the current insulin curve for a 1 unit bolus in 5 minute steps
        Treatment dummyBolus = new Treatment();
        dummyBolus.setTreatment(1, now);
        InsulinInterface insulinInterface = ConfigBuilderPlugin.getPlugin().getActiveInsulin();
        double dia = insulinInterface.getDia();
        JSONArray mBolusActivityCurve = new JSONArray();
        double activityContribCumalative = 0;
        double lastActivity = 0d;
        int tickSize = 5;
        for(int minutes = 0; minutes < dia*60; minutes+=tickSize ) {
            Iob insulin = insulinInterface.iobCalcForTreatment(dummyBolus, now + (minutes*1000*60), dia);
            activityContribCumalative += ((lastActivity + insulin.activityContrib) / 2) * tickSize;
            lastActivity = insulin.activityContrib;
            mBolusActivityCurve.put(Math.round(activityContribCumalative*1000d)/1000d);
        }
        mProfile.put("bolus_insulin_activity_curve", mBolusActivityCurve);

        // Calculate the basal curve from the above in 5 minute steps for a 1 unit/hour basal rate
        JSONArray mBasalActivityCurve = new JSONArray();
        double fiveMinuteBolusEquivalent = 1d / 12d;
        for(int pos = 0; pos < dia*12; pos++ ) {
            activityContribCumalative = 0;
            for(int offset = 0; offset <= pos; offset++ ) {
                activityContribCumalative += mBolusActivityCurve.getDouble(offset) * fiveMinuteBolusEquivalent;
            }
            mBasalActivityCurve.put(Math.round(activityContribCumalative*1000d)/1000d);
        }
        mProfile.put("basal_insulin_activity_curve", mBasalActivityCurve);

        // Calculate the inslin curve if we split the bolus into 'x' steps
        int splitIntoSteps = SP.getInt(R.string.key_apscurves_smb_steps, 4);
        if (mealData.mealCOB < 1) {
            splitIntoSteps = SP.getInt(R.string.key_apscurves_smb_steps_uam, 4);
        }

        JSONArray mBolusStepsActivityCurve = new JSONArray();
        for(int pos = 0; pos < dia*12; pos++ ) {
            activityContribCumalative = 0;
            for(int steps = Math.max(0, (pos - splitIntoSteps)+1); steps <= pos; steps++ ) {
                activityContribCumalative += mBolusActivityCurve.getDouble(steps);
            }
            mBolusStepsActivityCurve.put(Math.round(activityContribCumalative*1000d)/1000d);
        }
        mProfile.put("split_bolus_insulin_activity_curve", mBolusStepsActivityCurve);



        TemporaryBasal tb = TreatmentsPlugin.getPlugin().getTempBasalFromHistory(now);

        mCurrentTemp = new JSONObject();
        mCurrentTemp.put("temp", "absolute");
        mCurrentTemp.put("duration", tb != null ? tb.getPlannedRemainingMinutes() : 0);
        mCurrentTemp.put("rate", tb != null ? tb.tempBasalConvertedToAbsolute(now, profile) : 0d);

        // as we have non default temps longer than 30 mintues
        TemporaryBasal tempBasal = TreatmentsPlugin.getPlugin().getTempBasalFromHistory(System.currentTimeMillis());
        if (tempBasal != null) {
            mCurrentTemp.put("minutesrunning", tempBasal.getRealDuration());
        }

        mIobData = IobCobCalculatorPlugin.convertToJSONArray(iobArray);

        mGlucoseStatus = new JSONObject();
        mGlucoseStatus.put("glucose", glucoseStatus.glucose);

        if (SP.getBoolean(R.string.key_always_use_shortavg, false)) {
            mGlucoseStatus.put("delta", glucoseStatus.short_avgdelta);
        } else {
            mGlucoseStatus.put("delta", glucoseStatus.delta);
        }
        mGlucoseStatus.put("short_avgdelta", glucoseStatus.short_avgdelta);
        mGlucoseStatus.put("long_avgdelta", glucoseStatus.long_avgdelta);
        mGlucoseStatus.put("date", glucoseStatus.date);

        mMealData = new JSONObject();
        mMealData.put("carbs", mealData.carbs);
        mMealData.put("boluses", mealData.boluses);
        mMealData.put("mealCOB", mealData.mealCOB);
        mMealData.put("slopeFromMaxDeviation", mealData.slopeFromMaxDeviation);
        mMealData.put("slopeFromMinDeviation", mealData.slopeFromMinDeviation);
        mMealData.put("lastBolusTime", mealData.lastBolusTime);
        mMealData.put("lastCarbTime", mealData.lastCarbTime);
        mMealData.put("lastSignificantCarbTime", mealData.lastSignificantCarbTime);
        mMealData.put("deviation", mealData.deviation);

        JSONArray mPredicatedCarbDeviations = new JSONArray();

        for(int i = 0; i < predicatedCarbDeviations.size(); ++i) {
            mPredicatedCarbDeviations.put(predicatedCarbDeviations.get(i));
        }
        mMealData.put("predicatedCarbDeviations", mPredicatedCarbDeviations);


        AutosensData autosensData = IobCobCalculatorPlugin.getPlugin().getLastAutosensDataSynchronized("OpenAPSPlugin");
        mMealData.put("activeCarbsDetails", autosensData.getActiveCarbsDescription());

        mMealData.put("confidence", autosensData.getDevationPredictionConfidence());

        if (MainApp.getConstraintChecker().isAutosensModeEnabled().value()) {
            mAutosensData = new JSONObject();
            mAutosensData.put("ratio", autosensDataRatio);
        } else {
            mAutosensData = new JSONObject();
            mAutosensData.put("ratio", 1.0);
        }
        mMicrobolusAllowed = true;//microBolusAllowed;
        mSMBAlwaysAllowed = true;//advancedFiltering;

    }

    private Object makeParam(JSONObject jsonObject, Context rhino, Scriptable scope) {

        if (jsonObject == null) return Undefined.instance;

        Object param = NativeJSON.parse(rhino, scope, jsonObject.toString(), (context, scriptable, scriptable1, objects) -> objects[1]);
        return param;
    }

    private Object makeParamArray(JSONArray jsonArray, Context rhino, Scriptable scope) {
        //Object param = NativeJSON.parse(rhino, scope, "{myarray: " + jsonArray.toString() + " }", new Callable() {
        Object param = NativeJSON.parse(rhino, scope, jsonArray.toString(), (context, scriptable, scriptable1, objects) -> objects[1]);
        return param;
    }

    private String readFile(String filename) throws IOException {
        byte[] bytes = mScriptReader.readFile(filename);
        String string = new String(bytes, StandardCharsets.UTF_8);
        if (string.startsWith("#!/usr/bin/env node")) {
            string = string.substring(20);
        }
        return string;
    }

}
