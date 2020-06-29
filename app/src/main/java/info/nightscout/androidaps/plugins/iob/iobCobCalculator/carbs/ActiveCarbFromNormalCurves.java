package info.nightscout.androidaps.plugins.iob.iobCobCalculator.carbs;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import info.nightscout.androidaps.plugins.treatments.Treatment;

public class ActiveCarbFromNormalCurves extends ActiveCarbFromTriangles {

    List<CarbsNormalDistribution> carbsDistributions = new ArrayList<>();

    ActiveCarbFromNormalCurves(Treatment t) {
        super(t);
/*
            // Magic number - assume not more than 15g or 1/3 of fast sugar as a starting point
            // This is to avoid a large meal predicting a huge inital spike
            // If it turns out to be inacurate the distribution estimation code should spot it
            double maxPredictedFastCarbs = 15;
            double fastCarbsTotal = Math.min(maxPredictedFastCarbs,carbs/4);
            carbsDistributions.add(new CarbsNormalDistribution(fastCarbsTotal*0.3, 10, 19)); // Very Fast (pure glucose - think hypo treatment)
            carbsDistributions.add(new CarbsNormalDistribution(fastCarbsTotal*0.7, 30, 59)); // Fast carbs
            carbsDistributions.add(new CarbsNormalDistribution((remaining - fastCarbsTotal)/3, 60, 119 )); // Medium carbs
            carbsDistributions.add(new CarbsNormalDistribution((remaining - fastCarbsTotal)/3, 120, 199 )); // Slow carbs
            carbsDistributions.add(new CarbsNormalDistribution((remaining - fastCarbsTotal)/3, 200, 300 )); // Fat and protein
*/

        // Initial fast carbs estimate
        carbsDistributions.add(new CarbsNormalDistribution(carbs/4, 30, 59));
        //carbsDistributions.add(new CarbsInPast.CarbsNormalDistribution(carbs/4, 60, 119));
        //carbsDistributions.add(new CarbsInPast.CarbsNormalDistribution(carbs/4, 120, 199));
        //carbsDistributions.add(new CarbsInPast.CarbsNormalDistribution(carbs/4, 200, 300));
    }

    ActiveCarbFromNormalCurves(ActiveCarbFromNormalCurves other) {
        super(other);
        this.carbsDistributions = new ArrayList<>(other.carbsDistributions);
    }

    @Override
    public double getPredictedCarbsConfidence() {
        return 0.5;
    }

    @Override
    public List<Double> getPredicatedCarbs(int numberOfDataPoints ) {

        CarbsNormalDistribution cd0 = null;
        if (carbsDistributions.size() > 0) {
            cd0 = carbsDistributions.get(0);
        }
        if (cd0 == null || carbAbsorptionHistory.size() > cd0.median / 5) {
            return super.getPredicatedCarbs(numberOfDataPoints);
        }

        List<Double> prediction = new ArrayList<>();

        double carbsLeftOver = remaining;

        // Count the carbs in the curves (over next 10 hours to make sure we get everything)
        for(int tick = 0; tick < 12*10; ++tick) {
            int minutes = (carbAbsorptionHistory.size() + tick) * tickSize;
            double carbsInTick = 0;
            for (int i = 0; i < carbsDistributions.size(); ++i) {
                CarbsNormalDistribution cd = carbsDistributions.get(i);
                double variance = Math.pow(cd.standardDeviation, 2);
                carbsInTick += cd.carbs * tickSize * ( 1 / Math.sqrt(2*Math.PI*variance)) * Math.pow(Math.E, -Math.pow(minutes-cd.median,2) / (2 * variance));
            }
            carbsLeftOver -= carbsInTick;
        }

        double ticksRemaining = Math.max(48, (maxAbsorptionHours*12) - carbAbsorptionHistory.size());
        double tickReminaingPeak = ticksRemaining / 2;
        double carbsLeftOverPeakCarbs = carbsLeftOver / tickReminaingPeak;

        for(int tick = 0; tick < numberOfDataPoints; ++tick) {
            int minutes = (carbAbsorptionHistory.size() + tick) * tickSize;
            double carbsInTick = 0;
            for (int i = 0; i < carbsDistributions.size(); ++i) {
                CarbsNormalDistribution cd = carbsDistributions.get(i);
                double variance = Math.pow(cd.standardDeviation, 2);
                carbsInTick += cd.carbs * tickSize * ( 1 / Math.sqrt(2*Math.PI*variance)) * Math.pow(Math.E, -Math.pow(minutes-cd.median,2) / (2 * variance));
            }

            double offsetFromTickPeak = (tickReminaingPeak - Math.abs(tickReminaingPeak - tick)) / tickReminaingPeak;
            carbsInTick += Math.max(0, carbsLeftOverPeakCarbs * offsetFromTickPeak);

            prediction.add(carbsInTick);
        }

        return prediction;
    }

    double getCalculatedCarbsRemining() {
        double carbsSoFar = 0d;
        for(int tick = 0; tick < carbAbsorptionHistory.size(); ++tick) {
            int minutes = tick * tickSize;
            for (int i = 0; i < carbsDistributions.size(); ++i) {
                CarbsNormalDistribution cd = carbsDistributions.get(i);
                double variance = Math.pow(cd.standardDeviation, 2);
                carbsSoFar += cd.carbs * tickSize * ( 1 / Math.sqrt(2*Math.PI*variance)) * Math.pow(Math.E, -Math.pow(minutes-cd.median,2) / (2 * variance));
            }
        }

        return carbs - carbsSoFar;
    }

    private void displaycarbsDistributions(ActiveCarbFromNormalCurves activeCarb) {

        System.out.println("At "+(activeCarb.carbAbsorptionHistory.size()*5)+" minutes");
        for (int i = 0; i < activeCarb.carbsDistributions.size(); ++i) {
            CarbsNormalDistribution cd = activeCarb.carbsDistributions.get(i);
            System.out.println(i+": carbs: "+cd.carbs+", median: "+cd.median+", stdDev: "+cd.standardDeviation);
        }
        System.out.println("");
    }

    private List<CarbsNormalDistribution> cloneCarbsList(List<CarbsNormalDistribution> currentList) {
        List<CarbsNormalDistribution> newList = new ArrayList<>();
        for (int i = 0; i < carbsDistributions.size(); ++i) {
            newList.add(new CarbsNormalDistribution(currentList.get(i)));
        }
        return newList;
    }

    double estimateCarbsDistributionFromAborptionHistory() {

        // Need at least 3 points to have a hope
        if (carbAbsorptionHistory.size() <= 3) {
            return scoreAgainstAbsorptionHistory(carbAbsorptionHistory, carbsDistributions);
        }

        // Find the highest peak, estimate and remove
        List<CarbsNormalDistribution> bestSoFarDistributionList = new ArrayList<>();

        double carbsRemaining = carbs;
        Map<Integer, Double> carbsHistoryReduced = new TreeMap(carbAbsorptionHistory);
        Map<Integer, Boolean> excluded = new TreeMap(carbAbsorptionHistory);
        for (Map.Entry<Integer, Double> entry : carbsHistoryReduced.entrySet()) {
            excluded.put(entry.getKey(), entry.getKey() < 11);
        }

        double maxPeakValue = 1d;
        while(carbsRemaining > 0 && maxPeakValue > 0.1) {
            long maxPeakTime = 0;
            maxPeakValue = 0d;
            for (Map.Entry<Integer, Double> entry : carbsHistoryReduced.entrySet()) {
                if (!excluded.get(entry.getKey()) && entry.getValue() > maxPeakValue) {
                    maxPeakValue = entry.getValue();
                    maxPeakTime = entry.getKey();
                }
            }

            if (maxPeakValue > 0.1) {

                //System.out.println("Peak: "+maxPeakTime+"', "+maxPeakValue);

                // inital guess - standard deviation is a quarter of the offset startTime and not more than 20 min
                double deviation = Math.min(20, maxPeakTime / 4);
                double variance = Math.pow(deviation, 2);
                double carbsInDistribution = Math.min(carbsRemaining, maxPeakValue / (tickSize * (1 / Math.sqrt(2 * Math.PI * variance))));

                CarbsNormalDistribution newDistribution = new CarbsNormalDistribution(carbsInDistribution, maxPeakTime, maxPeakTime * 2);
                newDistribution.median = maxPeakTime;
                newDistribution.standardDeviation = deviation;
                bestSoFarDistributionList.add(newDistribution);

                for (Map.Entry<Integer, Double> entry : carbsHistoryReduced.entrySet()) {
                    entry.setValue(Math.max(0d, entry.getValue() - newDistribution.get(entry.getKey())));
                }

                carbsRemaining -= carbsInDistribution;

                for (Map.Entry<Integer, Double> entry : carbsHistoryReduced.entrySet()) {
                    if (entry.getKey() > newDistribution.median - 2.5*newDistribution.standardDeviation && entry.getKey() < maxPeakTime + 2.5*newDistribution.standardDeviation)
                        excluded.put(entry.getKey(), true);
                }
            }
        }

        carbsDistributions = bestSoFarDistributionList;

        return 0;
/*
            Map<Long, Double> carbsHistorySmoothed = new TreeMap(carbAbsorptionHistory);
            double prevValue = 0;
            for (Long key : carbsHistorySmoothed.keySet()) {
                double currentValue = carbsHistorySmoothed.get(key);
//                carbsHistorySmoothed.put(key, (prevValue+currentValue)/2 );
                carbsHistorySmoothed.put(key, currentValue );
                prevValue = currentValue;
            }

            if (carbAbsorptionHistory.size() < 3) {
                return scoreAgainstAbsorptionHistory(carbsHistorySmoothed, carbsDistributions);
            }

            int currTimeSlot = carbAbsorptionHistory.size() * tickSize;

//            double carbsInPreviousDistributions = 0d;
            int start;
            for(start = 0; start < carbsDistributions.size() - 1; ++start) {
                if (currTimeSlot < carbsDistributions.get(start+1).minMedian) {
                    break;
                }
  //              carbsInPreviousDistributions += carbsDistributions.get(start).carbs;
            }

//            System.out.println("PrevCarbs:"+carbsInPreviousDistributions);
            System.out.println("Current:"+start);

            // Try a rough grid over allowed values
            CarbsNormalDistribution currentCd;// = carbsDistributions.get(current);
            CarbsNormalDistribution nextCd;// = carbsDistributions.get(current+1);

            List<CarbsNormalDistribution> bestSoFarDistributionList = cloneCarbsList(carbsDistributions);
            double bestScore = scoreAgainstAbsorptionHistory(carbsHistorySmoothed, bestSoFarDistributionList);
            System.out.println("before score: "+bestScore);

            // Really struggling with local minima - going back to a rough brute force approach

            double carbsInPreviousDistributions = 0d;
            for(int current = 0; current <= start && current < bestSoFarDistributionList.size() - 1; ++current) {
                List<CarbsNormalDistribution> evaluationDistributionList = cloneCarbsList(bestSoFarDistributionList);

                System.out.println("current = "+current);
                currentCd = evaluationDistributionList.get(current);

                double steps = 10;

                double minCarbs = 0;
                double carbsStepSize = ((carbs - carbsInPreviousDistributions) - minCarbs) / steps;
                if (carbsStepSize > 0) {
                    for (currentCd.carbs = 0; currentCd.carbs <= (carbs - carbsInPreviousDistributions); currentCd.carbs += carbsStepSize) {

                        int remainingDistributions =  (evaluationDistributionList.size() - current)-1;
                        if (remainingDistributions > 0) {
                            double leftOverCarbsDividedEqually = ((carbs - currentCd.carbs) - carbsInPreviousDistributions) / remainingDistributions;
                            for (int k = current + 1; k < evaluationDistributionList.size(); ++k) {
                                evaluationDistributionList.get(k).carbs = leftOverCarbsDividedEqually;
                            }
                        }

                        double mediaStepSize = (currentCd.maxMedian - currentCd.minMedian) / steps;
                        for (currentCd.median = currentCd.minMedian; currentCd.median < currentCd.maxMedian; currentCd.median += mediaStepSize) {

                            double standardDeviationStepSize = ((currentCd.median / 2) - (currentCd.median / 4)) / (steps/2);
                            for (currentCd.standardDeviation = currentCd.median / 4; currentCd.standardDeviation < currentCd.median / 2; currentCd.standardDeviation += standardDeviationStepSize) {

                                double newScore = scoreAgainstAbsorptionHistory(carbsHistorySmoothed, evaluationDistributionList);
                                if (newScore < bestScore) {
                                    bestSoFarDistributionList = cloneCarbsList(evaluationDistributionList);
                                    bestScore = newScore;
                                }
                            }
                        }
                    }
                }

                carbsInPreviousDistributions += bestSoFarDistributionList.get(current).carbs;
            }

            carbsDistributions = bestSoFarDistributionList;
//            bestScore = scoreAgainstAbsorptionHistory(carbsHistorySmoothed, carbsDistributions);

            System.out.println("result score: "+bestScore);

            double carbsFromDeviationsSoFar = 0d;
            for (Map.Entry<Long, Double> entry : carbAbsorptionHistory.entrySet()) {
                carbsFromDeviationsSoFar += entry.getValue();
            }
            System.out.println("carbs from deviations:"+carbsFromDeviationsSoFar);

            return bestScore;

*/
    }

    private double scoreAgainstAbsorptionHistory(Map<Integer, Double> carbsHistorySmoothed, List<CarbsNormalDistribution> testSet) {

        double score = 0d;

        for (Map.Entry<Integer, Double> entry : carbsHistorySmoothed.entrySet()) {
            double carbsInTick = 0;
            for (int i = 0; i < testSet.size(); ++i) {
                CarbsNormalDistribution cd = testSet.get(i);
                double variance = Math.pow(cd.standardDeviation, 2);
                carbsInTick += cd.carbs * tickSize * (1 / Math.sqrt(2 * Math.PI * variance)) * Math.pow(Math.E, -Math.pow(entry.getKey() - cd.median, 2) / (2 * variance));
            }
            score += Math.pow(entry.getValue() - carbsInTick, 2);
        }

        score = score / carbAbsorptionHistory.size();

        return score;
    }

}
            /*
            //System.out.println("halfway score: "+bestScore);
            //System.out.println("candiadates: "+bestResults.size());
            //displaycarbsDistributions(this);

            // Try linear regression on the best results from the above to try and improve
            double[] factors = {0.99, 1.01};
            bestResultsPoolSize = 10;

            for(int loops = 0; loops < 10; loops++) {

                Map<Double, List<CarbsNormalDistribution>> currentBestResults = new TreeMap<>(bestResults);

                for (Map.Entry<Double, List<CarbsNormalDistribution>> entry : currentBestResults.entrySet()) {

                    evaluationDistributionList = cloneCarbsList(entry.getValue());

                    for (int i = 0; i < evaluationDistributionList.size(); ++i) {
                        CarbsNormalDistribution evalCd = evaluationDistributionList.get(i);

                        double currentVal = 0;

                        currentVal = evalCd.carbs;
                        for (int j = 0; j < factors.length; ++j) {
                            evalCd.carbs = currentVal * factors[j];
                            if (i > 0 || evalCd.carbs < carbs) {
                                double newScore = scoreAgainstAbsorptionHistory(evaluationDistributionList);
                                if (newScore < bestResults.lastKey()) {
                                    if (bestResults.size() >= bestResultsPoolSize) {
                                        bestResults.remove(bestResults.lastKey());
                                    }
                                    bestResults.put(newScore, cloneCarbsList(evaluationDistributionList));
                                }
                            }
                        }
                        evalCd.carbs = currentVal;

                        if (evalCd.carbs > 0) {
                            currentVal = evalCd.median;
                            for (int j = 0; j < factors.length; ++j) {
                                evalCd.median = currentVal * factors[j];
                                if (evalCd.median > evalCd.minMedian && evalCd.median < evalCd.maxMedian) {
                                    double newScore = scoreAgainstAbsorptionHistory(evaluationDistributionList);
                                    if (newScore < bestResults.lastKey()) {
                                        if (bestResults.size() >= bestResultsPoolSize) {
                                            bestResults.remove(bestResults.lastKey());
                                        }
                                        bestResults.put(newScore, cloneCarbsList(evaluationDistributionList));
                                    }
                                }
                            }
                            evalCd.median = currentVal;

                            currentVal = evalCd.standardDeviation;
                            for (int j = 0; j < factors.length; ++j) {
                                evalCd.standardDeviation = currentVal * factors[j];
                                if (evalCd.standardDeviation > evalCd.median / 8 && evalCd.median < evalCd.median / 2) {
                                    double newScore = scoreAgainstAbsorptionHistory(evaluationDistributionList);
                                    if (newScore < bestResults.lastKey()) {
                                        if (bestResults.size() >= bestResultsPoolSize) {
                                            bestResults.remove(bestResults.lastKey());
                                        }
                                        bestResults.put(newScore, cloneCarbsList(evaluationDistributionList));
                                    }
                                }
                            }
                            evalCd.standardDeviation = currentVal;
                        }
                    }
                }
            }

            carbsDistributions = bestResults.get(bestResults.firstKey());
            bestScore = scoreAgainstAbsorptionHistory(carbsDistributions);
*/

                        /*
            CarbsNormalDistribution cd = carbsDistributions.get(0);
            CarbsNormalDistribution cd2 = carbsDistributions.get(1);

            double bestScore = scoreAgainstAbsorptionHistory(carbsDistributions);
            System.out.println("before score: "+bestScore);

            List<CarbsNormalDistribution> search = new ArrayList<CarbsNormalDistribution>();
            for (int i = 0; i < carbsDistributions.size(); ++i) {
                search.add(new CarbsNormalDistribution(carbsDistributions.get(i)));
            }

            CarbsNormalDistribution fastCd = search.get(0);
            CarbsNormalDistribution mediumCd = search.get(1);

            // Try brute force just to see what happens
            for (fastCd.carbs = Math.floor(cd.carbs * 0.5); fastCd.carbs <= carbs; ++fastCd.carbs) {
                mediumCd.carbs = carbs - fastCd.carbs;
                for (fastCd.median = 15; fastCd.median < 40; ++fastCd.median) {
                    for (fastCd.standardDeviation = 1; fastCd.standardDeviation < fastCd.median / 2; ++fastCd.standardDeviation) {
                        for (mediumCd.median = 45; mediumCd.median < 80; mediumCd.median += 5) {
                            for (mediumCd.standardDeviation = 1; mediumCd.standardDeviation < mediumCd.median / 2; mediumCd.standardDeviation += 3) {
                                double newScore = scoreAgainstAbsorptionHistory(search);
                                if (newScore < bestScore) {
                                    bestScore = newScore;
                                    cd.carbs = fastCd.carbs;
                                    cd.median = fastCd.median;
                                    cd.standardDeviation = fastCd.standardDeviation;
                                    cd2.carbs = mediumCd.carbs;
                                    cd2.median = mediumCd.median;
                                    cd2.standardDeviation = mediumCd.standardDeviation;
                                }
                            }
                        }
                    }
                }
            }

            double carbsFromDeviationsSoFar = 0d;
            for (Map.Entry<Long, Double> entry : carbAbsorptionHistory.entrySet()) {
                carbsFromDeviationsSoFar += entry.getValue();
            }
            System.out.println("after score: "+bestScore);
            System.out.println("carbs from deviations:"+carbsFromDeviationsSoFar);

            return bestScore;
             */
