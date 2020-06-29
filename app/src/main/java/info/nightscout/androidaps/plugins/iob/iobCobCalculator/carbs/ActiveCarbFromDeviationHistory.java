package info.nightscout.androidaps.plugins.iob.iobCobCalculator.carbs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.treatments.Treatment;

public class ActiveCarbFromDeviationHistory extends ActiveCarbFromTriangles {

    static class MealTypeCarbsDeviation {

        String matchString;
        double[] deviations;
        private static Logger log = LoggerFactory.getLogger(L.CARBS);

        MealTypeCarbsDeviation(String matchString, String historyCSV) {
            this.matchString = matchString.toLowerCase();

            String[] splitData = historyCSV.split(",");

            String date = splitData[0];
            String notes = splitData[1];
            Double carbs = Double.parseDouble(splitData[2]);
            String insulinGiven = splitData[3];
            String discarded = splitData[4];

            deviations = new double[splitData.length - 5];
            for(int i = 5; i < splitData.length; i++){
                deviations[i-5] = Double.parseDouble(splitData[i]) / carbs;
            }
        }

        boolean matches(String label) {
            if (label == null) return false;
            return label.toLowerCase().contains(matchString);
        }
    }

    private static final MealTypeCarbsDeviation[] mealTypeCarbDeviations = {
            new MealTypeCarbsDeviation("MinimalXXXX", "DATE,Generated,1,0,0,0,0,0.0025419809666057,0.0078988147955217,0.012087882736246,0.010246147733101,0.01227613034094,0.014977704638364,0.016445516180535,0.008236279134933,0.0060164527711614,0.0059043317821865,0.0089912786604551,0.010705101268138,0.01171851656833,0.010084005531633,0.019436814240539,0.03210439000439,0.030501403100091,0.020249246510267,0.0048600581481528,0.0044078329358475,0.0048304722844412,0.0097423077740951,0.015642056562124,0.0077768398603706,0.00049838261730734,0.0018688258807876,0.0025828964605923,0.0040063527978222,0.0047740695088539,0.0040999393578754,0.00048892914653786,0,0,0,0,0,0,0.00029769036130223,0.00090978410406817,0.0022429855696596,0.0035041102889329,0.0026810095787515,0.00030900379928556,0.0010061258147148,0.0019286728017655,0.0029514630533547,0.0023579650251899,0.0024413999895884,0.0022549662667738,0.002428001613513,0.0020280563291623,0.0014611328129785,0.0011555517935979,0.0010877227777966,0.0015835471459778,0.0017956655619202,0.0018640265457011,0.0015134828358501,0.0027364072472624,0.0042101973239655,0.0036948375467989,0.0034813743760251,0.0034614776443571,0.0035663229731702,0.0028228220580345,0.0021405986534387,0.0019111547905424,0.0015589304852811,0.00093027299098797,0.00027118919855579"),
            /*
            new MealTypeCarbsDeviation("Porridge", "2019-11-05T09:48:55Z,Porridge with caramel sauce,30,5.6,0,0.0,0.17009345794392525,0.7176035035441781,1.6485665267054948,2.5370061853808297,2.469325928935844,2.2942074134272428,1.855652625400695,1.5796379069032462,1.1214569506523997,0.9785430493476004,0.8258160639306517,0.7412750011287194,0.5591087633753219,0.3638584134723917,0.41568919590049225,0.37316583141451093,0.4964535644950111,0.5253871506614295,0.4697796740259154,0.41090116935301824,0.2674816018781887,0.20533206916790836,0.3155356900988759,0.25338615738859543,0.2790487155176306,0.3892523364485982,0.24910379701115176,0.20985146056255366,0.0,0.14115987177750694,0.18971962616822433,0.23827938055894174,0.21865321233464263,0.20556910018510993,0.21211115625987628,0.31627838728610774,0.09536547925414243,0.044396808088481975,0.07936896386826617,0.10120201482897105,0.18991793938774715,0.26630647918093286,0.35812801931182897,0.3523602773921863"),
            new MealTypeCarbsDeviation("Waffle", "2019-11-04T09:53:41Z,Waffle blueberries and cream,29,4.2,0,0.0,0.2921328276671633,0.36787213869700663,0.8303354553252967,1.2633595196171383,1.6669443315725316,1.7940087588604454,1.3942006411124654,1.2283895435459842,0.9682242990654205,1.2054923472843018,1.2605941577497857,1.2246128493385706,1.097548422050657,0.889213959998194,0.8466905955122128,0.8008962029888483,0.7518307824281008,0.5565804325251705,0.45568422953632226,0.44260011738678945,0.5042439839270396,0.4780757596279742,0.27955438168766095,0.16230303851189687,0.2141338209399973,0.17815251252878242,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.03321594654386205,0.0,0.0,0.0,0.0,0.0,0.0,0.0"),
            new MealTypeCarbsDeviation("Protein", "2019-11-05T15:05:10Z,Protein Bar,12,1.2,0,0.4265900251658661,0.42592748198715613,0.21389026281897364,0.3065731124576464,0.5484449411141326,0.2844063955372593,0.1802928081079952,0.12207227323586611,0.4811997364550083,0.4749402815658276,0.42145871371105664,0.37172544686391407,0.4294369562011062,0.3773096165912619,0.3268436410234597,0.43375022796927337,0.4825908093160342,0.4171252917534789,0.4066494310396252,0.2841501779039124,0.33142087976119555,0.3750085087902192,0.3094613717469182,0.24305828030742246,0.28947357659298356,0.22205164137829347,0.2193940558303178,0.2190780309899893,0.2789231965826739,0.1284116795853053"),
            new MealTypeCarbsDeviation("Croissant", "2019-11-10T10:52:59Z,Croissants,40,6.4,1.3513351634554,0.0,0.3255,0.49645893719806766,1.206705314009662,2.2976570048309175,2.6737801932367153,2.1694927536231883,2.313533816425121,2.267492753623188,2.0278695652173915,1.6862053140096618,1.5066231884057968,1.225,0.5982125603864735,0.6337536231884059,0.9229178743961353,1.0325000000000002,0.8849589371980677,0.3182125603864734,0.4207946859903381,0.0,0.0,0.04871256038647344,0.4049178743961353,0.08829468599033813,0.10633574879227059,0.13137681159420292,0.0,0.0,0.0,0.0,0.20076466190806794,0.27565190106928245,0.12779217706364213,0.08057643101806096,0.09365869762807569,0.1068248267691422,0.1300634696048042,0.17190327245251202,0.25849984677197047,0.29339020080359046,0.3076237875747928,0.24320353563014255,0.22333459387534038,0.2223881571674357,0.24076747727989953,0.24612572588498155,0.19972297787131613,0.14670409408132476,0.12333986461330203,0.10857086520463324,0.12178072140551104,0.12353867307400455,0.08759402752403754,0.10528382324336845,0.13799580148296592,0.044775775909168335,0.06024756915597403,0.06663154043497285,0.04301143632394346,0.02868818671110573,0.01569126286380352,0.004565554139437252,0.0,9.364211597712645E-4,0.0,0.0027562926181670857,0.0049485125615760725,0.004590607640547318,0.0038590543344281377,0.0,0.0,0.0")

             */
            //new MealTypeCarbsDeviation("", ""),
    };

    MealTypeCarbsDeviation mealTypePredicatedCarbDeviations = null;

    public ActiveCarbFromDeviationHistory(Treatment t) {
        super(t);
        for(int i = 0; i < mealTypeCarbDeviations.length; ++i) {
            if (mealTypeCarbDeviations[i].matches(label)) {
                this.mealTypePredicatedCarbDeviations = mealTypeCarbDeviations[i];
                break;
            }
        }
    }

    public ActiveCarbFromDeviationHistory(ActiveCarbFromDeviationHistory other) {
        super(other);
        this.mealTypePredicatedCarbDeviations = other.mealTypePredicatedCarbDeviations;
    }

    @Override
    public ActiveCarb clone() {
        return new ActiveCarbFromDeviationHistory(this);
    }

    @Override
    public double getPredictedCarbsConfidence() {
        if (mealTypePredicatedCarbDeviations != null) {
            return 1;
        }
        return 0.5;
    }

    @Override
    public List<Double> getPredicatedCarbs(int numberOfDataPoints) {

        int existingSize = carbAbsorptionHistory.size();

        if (mealTypePredicatedCarbDeviations == null) {
            List<Double> prediction = super.getPredicatedCarbs(numberOfDataPoints);

            // Apply our 'minimum expected carbs' over the top
            int offset = 0;
            for (int tick = existingSize; tick < (numberOfDataPoints + existingSize); ++tick) {
                if (tick < mealTypeCarbDeviations[0].deviations.length) {
                    double minExpectedDev = mealTypeCarbDeviations[0].deviations[tick] * carbs;
                    if (minExpectedDev > prediction.get(offset)) {
                        prediction.set(offset, minExpectedDev);
                    }
                }

                offset++;
            }

            return prediction;
        }


        /*
        // rescale based on how well we are matching so far (allow for activity)
        double scaleFactor = 1;
        if (existingSize > 2) {
            double carbsSoFarActual = carbs - (remaining + discarded);
            double carbsSoFarPredicated = 0d;
            for (int tick = 0; tick < existingSize && tick < mealTypePredicatedCarbDeviations.deviations.length; ++tick) {
                carbsSoFarPredicated += mealTypePredicatedCarbDeviations.deviations[tick] * carbs;
            }
            if (carbsSoFarPredicated > 0.5) {
                scaleFactor = carbsSoFarActual / carbsSoFarPredicated;
            }
        }
         */

        List<Double> prediction = new ArrayList<>();

        for(int tick = existingSize; tick < (numberOfDataPoints + existingSize); ++tick) {
            if (tick >= mealTypePredicatedCarbDeviations.deviations.length) {
                prediction.add(0d);
            } else {
                prediction.add(mealTypePredicatedCarbDeviations.deviations[tick] * carbs);
            }
        }

        return prediction;
    }

}
