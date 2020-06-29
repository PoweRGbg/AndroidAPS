package info.nightscout.androidaps.plugins.iob.iobCobCalculator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.DataPointWithLabelInterface;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.PointsWithLabelGraphSeries;
import info.nightscout.androidaps.plugins.general.overview.graphExtensions.Scale;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.carbs.ActiveCarb;
import info.nightscout.androidaps.plugins.sensitivity.SensitivityAAPSPlugin;
import info.nightscout.androidaps.plugins.sensitivity.SensitivityWeightedAveragePlugin;
import info.nightscout.androidaps.utils.SP;

/**
 * Created by mike on 25.04.2017.
 */

public class AutosensData implements DataPointWithLabelInterface {
    private static Logger log = LoggerFactory.getLogger(L.AUTOSENS);
    public Float caloriesUsed;

    public void setChartTime(long chartTime) {
        this.chartTime = chartTime;
    }

    public long time = 0L;
    public double bg = 0; // mgdl
    private long chartTime;
    public String pastSensitivity = "";
    public double deviation = 0d;
    public boolean validDeviation = false;
    public List<ActiveCarb> activeCarbsList = new ArrayList<>();
    double absorbed = 0d;
    double discarded = 0d;
    public double carbsFromBolus = 0d;
    public double cob = 0;
    public double bgi = 0d;
    public double delta = 0d;
    public double avgDelta = 0d;
    public double avgDeviation = 0d;


    public AutosensResult autosensResult = new AutosensResult();
    public double slopeFromMaxDeviation = 0;
    public double slopeFromMinDeviation = 999;
    public double usedMinCarbsImpact = 0d;
    public boolean failoverToMinAbsorbtionRate = false;

    // Oref1
    public boolean absorbing = false;
    public double mealCarbs = 0;
    public int mealStartCounter = 999;
    public String type = "";
    public boolean uam = false;
    public List<Double> extraDeviation = new ArrayList<>();

    @Override
    public String toString() {
        return String.format(Locale.ENGLISH, "AutosensData: %s pastSensitivity=%s  delta=%.02f  avgDelta=%.02f bgi=%.02f deviation=%.02f avgDeviation=%.02f absorbed=%.02f carbsFromBolus=%.02f cob=%.02f autosensRatio=%.02f slopeFromMaxDeviation=%.02f slopeFromMinDeviation=%.02f activeCarbsList=%s",
                new Date(time).toLocaleString(), pastSensitivity, delta, avgDelta, bgi, deviation, avgDeviation, absorbed, carbsFromBolus, cob, autosensResult.ratio, slopeFromMaxDeviation, slopeFromMinDeviation, activeCarbsList.toString());
    }

    public List<ActiveCarb> cloneCarbsList() {
        List<ActiveCarb> newActiveCarbsList = new ArrayList<>();

        for(ActiveCarb c: activeCarbsList) {
            newActiveCarbsList.add(c.clone());
        }

        return newActiveCarbsList;
    }

    // remove carbs older than timeframe
    public void removeOldCarbs(long toTime) {
        double maxAbsorptionHours = Constants.DEFAULT_MAX_ABSORPTION_TIME;
        if (SensitivityAAPSPlugin.getPlugin().isEnabled(PluginType.SENSITIVITY) || SensitivityWeightedAveragePlugin.getPlugin().isEnabled(PluginType.SENSITIVITY)) {
            maxAbsorptionHours = SP.getDouble(R.string.key_absorption_maxtime, Constants.DEFAULT_MAX_ABSORPTION_TIME);
        } else {
            maxAbsorptionHours = SP.getDouble(R.string.key_absorption_cutoff, Constants.DEFAULT_MAX_ABSORPTION_TIME);
        }
        for (int i = 0; i < activeCarbsList.size(); i++) {
            ActiveCarb c = activeCarbsList.get(i);
            if (c.expiredAt(toTime)) {
                activeCarbsList.remove(i--);
                c.outputHistory();
                if (L.isEnabled(L.AUTOSENS))
                    log.debug("Removing carbs at " + new Date(toTime).toLocaleString() + " after " + maxAbsorptionHours + "h > " + c.toString());
            }
        }
    }

    private static double CARB_PRECISSION = 0.01;

    private void subtractCarbs(double carbsToSubtract, boolean isDiscard) {

        carbsToSubtract = Math.max(0, carbsToSubtract);

        // Assumption: carbs are absorbed roughly in proportion to how long ago they were eaten
        // So it is more likely they we are absorbing carbs from recent food than older food

        if (!isDiscard) {
            for (int i = 0; i < activeCarbsList.size(); i++) {
                ActiveCarb c = activeCarbsList.get(i);
                c.setCarbsAt(time, 0);
            }
        }

        // Allow for rounding with doubles
        while (carbsToSubtract > CARB_PRECISSION) {

            // Find all relevant carbs and total minutes of carb absorbsion left
            List<ActiveCarb> relevantCarbs = new ArrayList<>();
            double totalRemainingTimeMinutes = 0;

            for (int i = 0; i < activeCarbsList.size(); i++) {
                ActiveCarb c = activeCarbsList.get(i);
                if (c.startedAt(time)) {
                    double cCarbs = c.getCarbsRemaining();
                    if (cCarbs > 0) {
                        double remainingTimeMinutes = c.timeRemaining(time) / (60 * 1000L);
                        if (remainingTimeMinutes > 0) {
                            totalRemainingTimeMinutes += remainingTimeMinutes;
                            relevantCarbs.add(c);
                        }
                    }
                }
            }

            if (relevantCarbs.size() < 1) {
                break;
            }

            double carbsToSubtractRemaining = carbsToSubtract;

            for (int i = 0; i < relevantCarbs.size(); i++) {
                ActiveCarb c = relevantCarbs.get(i);

                double remainingTimeMinutes = c.timeRemaining(time) / (60 * 1000L);

                double sub = carbsToSubtract * (remainingTimeMinutes / totalRemainingTimeMinutes);

                sub = c.addCarbsAt(time, sub, isDiscard);

                carbsToSubtractRemaining -= sub;
            }

            carbsToSubtract = carbsToSubtractRemaining;
        }
    }

    protected double getCaloriesAsCarbs() {
        double restingCarbs = SP.getDouble(R.string.key_apscurves_resting_calories, 5d);
        double calPerCarb = SP.getDouble(R.string.key_apscurves_carbs_to_calories, 6d);
        if (caloriesUsed == null) {
            return 0d;
        }
        return Math.max(0d, caloriesUsed - restingCarbs) / calPerCarb;
    }

    public void substractAbosorbedCarbs() {
        subtractCarbs(absorbed + getCaloriesAsCarbs(), false);
        subtractCarbs(discarded, true);
    }

    public List<Double> getPredicatedDeviations(int numberOfDataPoints) {
        List<Double> predictedDeviations = new ArrayList<>();

        double exersiceCarbs = getCaloriesAsCarbs();
        for (int p = 0; p < numberOfDataPoints; ++p) {
            predictedDeviations.add(-Math.max(0d, exersiceCarbs - (exersiceCarbs / 12 ) * p));
        }

        for (int i = 0; i < activeCarbsList.size(); ++i) {
            ActiveCarb c = activeCarbsList.get(i);
            List<Double> carbs = c.getPredicatedCarbs(numberOfDataPoints);
            for (int p = 0; p < carbs.size() && p < numberOfDataPoints; ++p) {
                predictedDeviations.set(p, predictedDeviations.get(p) + (carbs.get(p)));
            }
        }

        return predictedDeviations;
    }

    public void calculateDeviationsForErrors() {
        for (int i = 0; i < activeCarbsList.size(); ++i) {
            ActiveCarb c = activeCarbsList.get(i);
            c.calculateCarbsForFeedbackLoop();
        }
    }

    public double getDevationPredictionConfidence() {

        double confidence = 0;
        int active = 0;

        for (int i = 0; i < activeCarbsList.size(); ++i) {
            ActiveCarb c = activeCarbsList.get(i);
            confidence += c.getPredictedCarbsConfidence();
            active++;
        }

        if (active == 0) {
            return 0.5;
        }

        return Math.min(1, Math.max(0, confidence / active));
    }


    public String getActiveCarbsDescription() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < activeCarbsList.size(); ++i) {
            ActiveCarb c = activeCarbsList.get(i);
            out.append(c.toString());
        }
        /*
            out.append(" ");
            out.append(c.carbAbsorptionHistory.toString());
            out.append("\n");
            for (int j = 0; j < c.carbsDistributions.size(); ++j) {
                ActiveCarb.CarbsNormalDistribution cd = c.carbsDistributions.get(j);
                out.append(" ");
                out.append(j);
                out.append(": carbs = ");
                out.append(cd.carbs);
                out.append(", median = ");
                out.append(cd.median);
                out.append(", stdDev = ");
                out.append(cd.standardDeviation);
                out.append("\n");
            }
            out.append("\n");
        }

         */
        return out.toString();
    }


    // ------- DataPointWithLabelInterface ------

    private Scale scale;

    public void setScale(Scale scale) {
        this.scale = scale;
    }

    @Override
    public double getX() {
        return chartTime;
    }

    @Override
    public double getY() {
        return scale.transform(cob);
    }

    @Override
    public void setY(double y) {

    }

    @Override
    public String getLabel() {
        return null;
    }

    @Override
    public long getDuration() {
        return 0;
    }

    @Override
    public PointsWithLabelGraphSeries.Shape getShape() {
        return PointsWithLabelGraphSeries.Shape.COBFAILOVER;
    }

    @Override
    public float getSize() {
        return 0.5f;
    }

    @Override
    public int getColor() {
        return MainApp.gc(R.color.cob);
    }
}
