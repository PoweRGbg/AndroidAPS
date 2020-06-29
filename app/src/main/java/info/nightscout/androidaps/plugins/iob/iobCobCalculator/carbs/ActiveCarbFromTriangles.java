package info.nightscout.androidaps.plugins.iob.iobCobCalculator.carbs;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.db.CareportalEvent;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.aps.openAPSSMB.SMBDefaults;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.nsclient.NSUpload;
import info.nightscout.androidaps.plugins.sensitivity.SensitivityAAPSPlugin;
import info.nightscout.androidaps.plugins.sensitivity.SensitivityWeightedAveragePlugin;
import info.nightscout.androidaps.plugins.treatments.Treatment;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.SP;

public class ActiveCarbFromTriangles implements ActiveCarb {

    static final int tickSize = 5;
    static final int predictionHistorySize = 60 / tickSize;

    protected static Logger log = LoggerFactory.getLogger(L.AUTOSENS);

    long startTime = 0L;
    double carbs = 0d;
    double remaining = 0d;
    double discarded = 0d;

    String label;

    double maxAbsorptionHours = Constants.DEFAULT_MAX_ABSORPTION_TIME;
    private double min5minCarbImpact = SMBDefaults.min_5m_carbimpact;

    SortedMap<Integer, Double> carbAbsorptionHistory = new TreeMap<>();

    List<Double> prediction = null;
    List<Double> carbPredictionHistory = new ArrayList<>();

    public ActiveCarbFromTriangles(Treatment t) {

        startTime = t.date;
        carbs = t.carbs;
        remaining = t.carbs;

        if (t.notes != null) {
            label = t.notes;
        }
        JSONObject bolusCalc = null;
        try {
            if (t.boluscalc != null) {
                bolusCalc = new JSONObject(t.boluscalc);
                if (bolusCalc.has("notes")) {
                    label = bolusCalc.getString("notes");
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        // First tick minutes is always zero
        carbAbsorptionHistory.put(0, 0d);

        // Backwards compatibility
        if (SensitivityAAPSPlugin.getPlugin().isEnabled(PluginType.SENSITIVITY) || SensitivityWeightedAveragePlugin.getPlugin().isEnabled(PluginType.SENSITIVITY)) {
            maxAbsorptionHours = SP.getDouble(R.string.key_absorption_maxtime, Constants.DEFAULT_MAX_ABSORPTION_TIME);
            Profile profile = ProfileFunctions.getInstance().getProfile(t.date);
            double sens = profile.getIsfMgdl(t.date);
            double ic = profile.getIc(t.date);
            min5minCarbImpact = t.carbs / (maxAbsorptionHours * 60 / 5) * sens / ic;
            if (L.isEnabled(L.AUTOSENS))
               log.debug("Min 5m carbs impact for " + carbs + "g @" + new Date(t.date).toLocaleString() + " for " + maxAbsorptionHours + "h calculated to " + min5minCarbImpact + " ISF: " + sens + " IC: " + ic);
        } else {
            min5minCarbImpact = SP.getDouble(R.string.key_openapsama_min_5m_carbimpact, SMBDefaults.min_5m_carbimpact);
        }
    }

    @Override
    public double get5minImpact() {
        return min5minCarbImpact;
    }

    ActiveCarbFromTriangles(ActiveCarbFromTriangles other) {
        this.startTime = other.startTime;
        this.carbs = other.carbs;
        this.remaining = other.remaining;
        this.carbAbsorptionHistory = new TreeMap<>(other.carbAbsorptionHistory);
        this.label = other.label;

        this.carbPredictionHistory = new ArrayList<>(other.carbPredictionHistory);
        if (other.prediction != null) {
            for (int i = 0; i < predictionHistorySize && i < other.prediction.size(); i++) {
                this.carbPredictionHistory.add(other.prediction.get(i));
            }
        }
        while (this.carbPredictionHistory.size() > predictionHistorySize * predictionHistorySize) {
            this.carbPredictionHistory.remove(0);
        }
    }

    @Override
    public ActiveCarb clone() {
        return new ActiveCarbFromTriangles(this);
    }

    @Override
    public void outputHistory() {
        String csv = StringUtils.join(carbAbsorptionHistory.values(), ',');

        log.info("Carb History Complete: @"+new Date(startTime).toLocaleString() + ", carbs = "+carbs+", remaining = "+remaining+" : "+ carbAbsorptionHistory.size() + " [" + csv + "] "+toString());

        try {
            JSONObject data = new JSONObject();
            data.put("enteredBy", "ActiveCarb");
            data.put("created_at", DateUtil.toISOString(startTime));
            data.put("eventType", CareportalEvent.CARBSCOMPLETE);
            data.put("originalCarbs", carbs);
            data.put("remaining", remaining);
            data.put("discarded", discarded);
            data.put("deviations", csv);
            data.put("label", label);
            NSUpload.uploadCareportalEntryToNS(data);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
    }

    protected int getTickAgeAt(long atTime) {
        return (int) Math.ceil((atTime - startTime) / (tickSize * 60 * 1000));
    }

    @Override
    public void setCarbsAt(long atTime, double value) {
        int tick = getTickAgeAt(atTime);
        carbAbsorptionHistory.put(tick, value);
    }

    @Override
    public double addCarbsAt(long atTime, double value, boolean isDiscard) {

        if (label != null && label.contains("celeriac")) {
            System.out.println("test");
        }

        value = Math.min(value, remaining);

        if (isDiscard) {
            discarded += value;
        } else {
            int tick = getTickAgeAt(atTime);
            carbAbsorptionHistory.put(tick, carbAbsorptionHistory.get(tick) + value);
        }
        remaining -= value;
        if (remaining <= 0.01) {
            remaining = 0;
            outputHistory();
        }
        return value;
    }

    @Override
    public boolean startedAt(long toTime) {
        return true;
    }

    @Override
    public boolean expiredAt(long toTime) {
        return startTime + (maxAbsorptionHours * 60 * 60 * 1000L) < toTime;
    }

    @Override
    public long timeRemaining(long atTime) {
        return (long)(startTime + maxAbsorptionHours * 60 * 60 * 1000L) - atTime;
    }

    @Override
    public double getCarbsRemaining() {
        return remaining;
    }

    @Override
    public double getPredictedCarbsConfidence() {
        return 0.5;
    }

    @Override
    public double carbsPredictionErrorFromHistory() {

        StringBuilder debugString = new StringBuilder("Prediction Feedback (carbs = ");

        double relevantError = 0;
        int recordCount = 0;

        debugString.append(carbs);
        debugString.append(")\n");

        // Experiment of a feedback loop...
        // Work out how accurate our last predictionHistorySize predictions were
        for (int i = 1; i <= 2; i++) { // predictionHistorySize
            int index = carbAbsorptionHistory.size() - i;
            if (index > 0) {
                Double actualDeviation = carbAbsorptionHistory.get(index);
                debugString.append(actualDeviation);
                debugString.append(":");

                double deviationError = 0;
                if (actualDeviation != null) {
                    double relevanceFactor = 1 / Math.pow(2,i);
                    for (int iPredictionHistory = carbPredictionHistory.size() - (i * predictionHistorySize);
                         iPredictionHistory >= 0; iPredictionHistory -= (predictionHistorySize - 1)) {

                        debugString.append(" ");
                        debugString.append(carbPredictionHistory.get(iPredictionHistory));

                        relevantError += (actualDeviation - carbPredictionHistory.get(iPredictionHistory));// * relevanceFactor;

                        deviationError += (actualDeviation - carbPredictionHistory.get(iPredictionHistory));// * relevanceFactor;

                        recordCount++;

                        relevanceFactor = relevanceFactor / 2;
                    }
                }
                debugString.append(" = ");
                debugString.append(deviationError);

                debugString.append("\n");
            }
        }
        if (recordCount > 0) {
            relevantError = relevantError / recordCount;
        }

        debugString.append("relevantError = ");
        debugString.append(relevantError);
        log.debug(debugString.toString());

        double feedbackPercentage = SP.getDouble(R.string.key_carbs_feedback_percentage, 0d);

        return relevantError * predictionHistorySize * (feedbackPercentage / 100);
    }

    @Override
    public List<Double> getPredicatedCarbs(int numberOfDataPoints) {

        // How good where the last x predictions? If they were low bias next prediction up, if high bias down
        // Very mild effect
        // Experimental
        double carbPredictionError = carbsPredictionErrorFromHistory();

        // Only generate a triangle covering the last 'x' minutes of carbs absorbsion
        // this is to reduce the tendency to over-predict for long lasting carbs
        final int maxCarbsMinutes = 60;

        prediction = new ArrayList<>();

        double carbsLeftOver = remaining;

        if (carbsLeftOver <= 0) {
            for (int tick = 0; tick < numberOfDataPoints; ++tick) {
                prediction.add(0d);
            }
            return prediction;
        }

        long durationTicks = 0 ;
        double lastCarbs = 0;
        if (carbAbsorptionHistory.size() > 0) {
            int lastTime = carbAbsorptionHistory.lastKey();
            lastCarbs = carbAbsorptionHistory.get(lastTime);

            // Average with the last but one carbs value (helps to avoid over-bolus due to noisy data)
            double prevLastCarbs = 0f;
            if (carbAbsorptionHistory.containsKey(lastTime-1)) {
                prevLastCarbs = carbAbsorptionHistory.get(lastTime-1);
            }
            lastCarbs = Math.min(lastCarbs, (lastCarbs + prevLastCarbs)/2);

            // Count the carbs in the last maxCarbsMinutes
            double carbsSince = 0;
            int sinceTime = lastTime - (maxCarbsMinutes / 5);
            for (int time : carbAbsorptionHistory.keySet()) {
                if (time >= sinceTime) {
                    carbsSince += carbAbsorptionHistory.get(time);
                }
            }

            // Yes, carbPredictionError can cause carbs to exceed remaining carbs
            // Works like UAM and helps to smooth transition between carbs model and UAM
            double relevantCarbs = Math.min(carbsSince, carbsLeftOver) + carbPredictionError;
            log.debug("(carbsSince = " + carbsSince + ", carbsLeftOver = " + carbsLeftOver + " ) + carbPredictionError = " + carbPredictionError);

            if (lastCarbs <= 0d) {
                durationTicks = 0;
            } else {
                // Assume we are currently at a peak - create a triangle to zero
                durationTicks = (long) Math.floor(relevantCarbs * 2 / lastCarbs);
            }

            if (durationTicks > 0) {
                for (int tick = 0; tick < durationTicks; ++tick) {

                    double offsetFromCurrentPeak = ((double) (durationTicks - tick)) / durationTicks;
                    double carbsFromDeviation = Math.max(0, lastCarbs * offsetFromCurrentPeak);

                    carbsLeftOver -= carbsFromDeviation;
                }
            }

            // Due to rounding or carbPredictionError we can exceed total carbs - make sure carbsLeftOver doesn't go negative
            carbsLeftOver = Math.max(0, carbsLeftOver);
        }

        double ticksRemaining = (maxAbsorptionHours*12) - durationTicks;
        double tickReminaingPeak = ticksRemaining / 2;
        double carbsLeftOverPeakCarbs = Math.max(0, carbsLeftOver / tickReminaingPeak);

        // In original oref0 leftover carbs are assumed to be a triangle starting now and peaking halfway to maxAbsorptionHours
        // but this can be an over prediction - move the start point back. 1 == left over carbs start at deviation peak end, 0 start immediately
        // Experimental - should be configuraable / autotune
        double leftOverCarbsPeakOffsetRatio = 0.5d;

        for(int tick = 0; tick < numberOfDataPoints; ++tick) {

            double carbsFromDeviation = 0;
            if (durationTicks > 0 && tick < durationTicks) {
                double offsetFromCurrentPeak = ((double) (durationTicks - tick)) / durationTicks;
                carbsFromDeviation = Math.max(0, lastCarbs * offsetFromCurrentPeak);
            }

            double offsetFromTickPeak = (tickReminaingPeak - Math.abs(tickReminaingPeak - (tick - (durationTicks * leftOverCarbsPeakOffsetRatio)))) / tickReminaingPeak;
            double leftOverCarbsThisTick = Math.max(0, carbsLeftOverPeakCarbs * offsetFromTickPeak);

            if (Double.isNaN(carbsFromDeviation)) carbsFromDeviation = 0;
            if (Double.isNaN(leftOverCarbsThisTick)) leftOverCarbsThisTick = 0;

            prediction.add(carbsFromDeviation + leftOverCarbsThisTick);
        }

        return prediction;
    }

    @Override
    public void calculateCarbsForFeedbackLoop() {
        getPredicatedCarbs(predictionHistorySize);
    }

    @Override
    public String toString() {
        return String.format(Locale.ENGLISH, "ActiveCarb: startTime: %s carbs: %.02f min5minCI: %.02f remaining: %.2f discarded: %.02f", new Date(startTime).toLocaleString(), carbs, 0d, remaining, discarded);
    }
}
