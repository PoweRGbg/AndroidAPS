/*
  Determine Basal

  Released under MIT license. See the accompanying LICENSE.txt file for
  full terms and conditions

  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
  THE SOFTWARE.
*/


var round_basal = require('../round-basal')

// Rounds value to 'digits' decimal places
function round(value, digits)
{
    if (! digits) { digits = 0; }
    var scale = Math.pow(10, digits);
    return Math.round(value * scale) / scale;
}

function maxInsulinForPredictedBGs(preBGs, insulinActivities, targetBGs, sens) {
    var maxOveralInsulin = 999;
    for (var i=0; i<preBGs.length && i < insulinActivities.length; i++) {
        if (insulinActivities[i] > 0) {
            var maxInsulin = Math.max(0,preBGs[i] - targetBGs[i]) / (insulinActivities[i] * sens);
            maxOveralInsulin = Math.min(maxOveralInsulin, maxInsulin);
        }
    }
    return round(maxOveralInsulin, 4);
}

function convert_bg(value, profile)
{
    if (profile.out_units == "mmol/L")
    {
        return round(value / 18, 1).toFixed(1);
    }
    else
    {
        return Math.round(value);
    }
}

var determine_basal = function determine_basal(glucose_status, currenttemp, iobArray, profile, autosens_data, meal_data, tempBasalFunctions, microBolusAllowed, reservoir_data) {

    var minThreshold = 80;

    var systemTime = new Date();

    // Setup result object
    rT = {
        'temp': 'absolute',
        'bg': glucose_status.glucose,
        'deliverAt' : systemTime, // The time at which the microbolus should be delivered
    };

    if (typeof profile === 'undefined' || typeof profile.current_basal === 'undefined') {
        rT.error ='Error: could not get current basal rate';
        return rT;
    }

    var profile_current_basal = round_basal(profile.current_basal, profile);
    var basal = profile_current_basal;

    var bgTime = new Date(glucose_status.date);
    var minAgo = round( (systemTime - bgTime) / 60 / 1000 ,1);

    var bg = glucose_status.glucose;

    // Try to handle loosing BG data gracefully
    var cgmFail = false;
    if (bg < 39) {  //Dexcom is in ??? mode or calibrating
        rT.reason = "CGM is calibrating or in ??? state";
        cgmFail = true;
    }
    if (minAgo > 12 || minAgo < -5) { // Dexcom data is too old, or way in the future
        rT.reason = "If current system time "+systemTime+" is correct, then BG data is too old. The last BG data was read "+minAgo+"m ago at "+bgTime;
        cgmFail = true;
    }
    if (cgmFail) {
        if (currenttemp.rate >= basal) { // high temp is running
            rT.reason += ". Canceling high temp basal of "+currenttemp.rate;
            rT.duration = 0;
            rT.rate = 0;
            return rT;
            //return tempBasalFunctions.setTempBasal(basal, 30, profile, rT, currenttemp);
        } else if ( currenttemp.rate == 0 && currenttemp.duration > 30 ) { //shorten long zero temps to 30m
            rT.reason += ". Shortening " + currenttemp.duration + "m long zero temp to 30m. ";
            rT.duration = 30;
            rT.rate = 0;
            return rT;
            //return tempBasalFunctions.setTempBasal(0, 30, profile, rT, currenttemp);
        } else { //do nothing.
            rT.reason += ". Temp " + currenttemp.rate + " <= current basal " + basal + "U/hr; doing nothing. ";
            return rT;
        }
    }

    if (typeof iobArray !== 'object' || iobArray.length < 1) {
        rT.error ='Error: iobArray undefined.';
        return rT;
    }

    // Get the first record from the iobArray - used for lots of things
    var iob_data = iobArray[0];

    if (typeof iob_data.activity === 'undefined' || typeof iob_data.iob === 'undefined' ) {
        rT.error ='Error: iob_data missing some property.';
        return rT;
    }

    if (typeof profile.max_iob === 'undefined' || isNaN(profile.max_iob) ) {
        rT.error ='Error: max_iob invalid.';
        return rT;
    }

    if (typeof profile.min_bg === 'undefined' || typeof profile.max_bg === 'undefined') {
        rT.error ='Error: could not determine target_bg.';
        return rT;
    }

    if (typeof profile.min_threshold === 'undefined') {
        rT.error ='Error: could not determine threshold.';
        return rT;
    }

    // Calculate target from min and max as average
    var min_bg = profile.min_bg;
    var max_bg = profile.max_bg;
    var target_bg = (profile.min_bg + profile.max_bg) / 2;

    // min_bg of 90 -> threshold of 65, 100 -> 70 110 -> 75, and 130 -> 85
    var threshold = Math.max(profile.min_threshold, min_bg - 0.5*(min_bg-40));
    console.log("threshold = ", threshold);

    // Determine sensitivity (with default of no-change)
    var sensitivityRatio = 1;

    if (typeof autosens_data !== 'undefined' ) {
        sensitivityRatio = sensitivityRatio * autosens_data.ratio;
        console.error("Autosens ratio: "+sensitivityRatio+"; ");
    }
    rT.sensitivityRatio = sensitivityRatio;

    // Adjust basal for sensitivity
    basal = round_basal(profile.current_basal * sensitivityRatio, profile);
    if (basal != profile_current_basal) {
        console.error("Adjusting basal from "+profile_current_basal+" to "+basal+"; ");
    } else {
        console.error("Basal unchanged: "+basal+"; ");
    }

    // Adjust ISF (sens) for sensitivity
    var profile_sens = round(profile.sens,1);
    var sens = round(profile.sens / sensitivityRatio, 1);
    if (sens != profile_sens) {
        console.error("ISF from "+profile_sens+" to "+sens);
    } else {
        console.error("ISF unchanged: "+sens);
    }

    // Carb ratio is not effected by sensitivity
    console.error("Carb Ratio:",profile.carb_ratio);

    // enable SMB whenever we have COB or UAM is enabled
    // SMB is disabled by default, unless explicitly enabled in preferences.json
    var enableSMB = false;
    if (! microBolusAllowed) {
        console.error("SMB disabled (!microBolusAllowed)")
    // disable SMB when a high temptarget is set
    } else if (! profile.allowSMB_with_high_temptarget && profile.temptargetSet && target_bg > 100) {
        console.error("SMB disabled due to high temptarget of",target_bg);
        enableSMB=false;
    // enable SMB/UAM (if enabled in preferences) while we have COB
    } else if (profile.enableSMB_with_COB === true && meal_data.mealCOB) {
        if (meal_data.bwCarbs) {
            if (profile.A52_risk_enable) {
                console.error("Warning: SMB enabled with Bolus Wizard carbs: be sure to easy bolus 30s before using Bolus Wizard")
                enableSMB=true;
            } else {
                console.error("SMB not enabled for Bolus Wizard COB");
            }
        } else {
            console.error("SMB enabled for COB of",meal_data.mealCOB);
            enableSMB=true;
        }
    // enable SMB/UAM (if enabled in preferences) for a full 6 hours after any carb entry
    // (6 hours is defined in carbWindow in lib/meal/total.js)
    } else if (profile.enableSMB_after_carbs === true && meal_data.carbs ) {
        if (meal_data.bwCarbs) {
            if (profile.A52_risk_enable) {
            console.error("Warning: SMB enabled with Bolus Wizard carbs: be sure to easy bolus 30s before using Bolus Wizard")
            enableSMB=true;
            } else {
                console.error("SMB not enabled for Bolus Wizard carbs");
            }
        } else {
            console.error("SMB enabled for 6h after carb entry");
            enableSMB=true;
        }
    // enable SMB/UAM (if enabled in preferences) if a low temptarget is set
    } else if (profile.enableSMB_with_temptarget === true && (profile.temptargetSet && target_bg < 100)) {
        if (meal_data.bwFound) {
            if (profile.A52_risk_enable) {
                console.error("Warning: SMB enabled within 6h of using Bolus Wizard: be sure to easy bolus 30s before using Bolus Wizard")
                enableSMB=true;
            } else {
                console.error("enableSMB_with_temptarget not supported within 6h of using Bolus Wizard");
            }
        } else {
            console.error("SMB enabled for temptarget of",convert_bg(target_bg, profile));
            enableSMB=true;
        }
    // enable SMB/UAM if always-on (unless previously disabled for high temptarget)
    } else if (profile.enableSMB_always === true) {
        if (meal_data.bwFound) {
            if (profile.A52_risk_enable === true) {
                console.error("Warning: SMB enabled within 6h of using Bolus Wizard: be sure to easy bolus 30s before using Bolus Wizard")
                enableSMB=true;
            } else {
                console.error("enableSMB_always not supported within 6h of using Bolus Wizard");
            }
        } else {
            console.error("SMB enabled due to enableSMB_always");
            enableSMB=true;
        }
    } else {
        console.error("SMB disabled (no enableSMB preferences active)");
    }

    // Current deltas
    var minDelta = Math.min(glucose_status.delta, glucose_status.short_avgdelta);
    var minAvgDelta = Math.min(glucose_status.short_avgdelta, glucose_status.long_avgdelta);
    var maxDelta = Math.max(glucose_status.delta, glucose_status.short_avgdelta, glucose_status.long_avgdelta);

    //calculate BG impact: the amount BG "should" be rising or falling based on insulin activity alone
    var bgi = round(( -iob_data.activity * sens * 5 ), 2);
    // project deviations for 30 minutes
    var deviation = round( 30 / 5 * ( minDelta - bgi ) );
    // don't overreact to a big negative delta: use minAvgDelta if deviation is negative
    if (deviation < 0) {
        deviation = round( (30 / 5) * ( minAvgDelta - bgi ) );
        // and if deviation is still negative, use long_avgdelta
        if (deviation < 0) {
            deviation = round( (30 / 5) * ( glucose_status.long_avgdelta - bgi ) );
        }
    }

    var csf = sens / profile.carb_ratio;
    console.log('CSF:', csf);

    var predicatedCIs = [];
    for (var i = 0; i < meal_data.predicatedCarbDeviations.length; i++) {
        predicatedCIs.push(meal_data.predicatedCarbDeviations[i] * csf);
    }
    console.log('CI deviations:', predicatedCIs);

    // carb impact and duration are 0 unless changed below
    var ci = 0;

    // calculate current carb absorption rate, and how long to absorb all carbs
    // CI = current carb impact on BG in mg/dL/5m
    ci = round((minDelta - bgi),1);
    uci = round((minDelta - bgi),1);
    // ISF (mg/dL/U) / CR (g/U) = CSF (mg/dL/g)

    var lastSignificantCarbAge = 999;
    if (meal_data.carbs) {
        lastSignificantCarbAge = round(( new Date().getTime() - meal_data.lastSignificantCarbTime ) / 60000);
    }

    // calculate peak deviation in last hour, and slope from that to current deviation
    var slopeFromMaxDeviation = round(meal_data.slopeFromMaxDeviation,2);
    // calculate lowest deviation in last hour, and slope from that to current deviation
    var slopeFromMinDeviation = round(meal_data.slopeFromMinDeviation,2);
    // assume deviations will drop back down at least at 1/3 the rate they ramped up
    var slopeFromDeviations = Math.min(slopeFromMaxDeviation,-slopeFromMinDeviation/3);

    // generate predicted future BGs based on IOB, COB, and current absorption rate
    var COBZTpredBGs = [ bg ];
    var UAMpredBGs = [ bg ];
    var UAMduration = 0;

    var IOBImpacts = [ ];

    try {
        // Limit length of iob array
        iobArray = iobArray.slice(0,48);

        iobArray.forEach(function(iobTick) {

            predZTBGI = round(( -iobTick.iobWithZeroTemp.activity * sens * 5 ), 2);
            IOBImpacts.push(predZTBGI);

            // predicted deviation impact drops linearly from current deviation down to zero
            // over 60 minutes (data points every 5m)
            predNegativeDev = Math.min(0, ci * ( 1 - Math.min(1,COBZTpredBGs.length/(30/5)) ));

            predNegativeDev = 0;

            COBZTpredBG = COBZTpredBGs[COBZTpredBGs.length-1] + predZTBGI + predNegativeDev + (predicatedCIs[COBZTpredBGs.length-1]);

            // for UAMpredBGs, predicted carb impact drops at slopeFromDeviations
            // calculate predicted CI from UAM based on slopeFromDeviations
            predUCIslope = Math.max(0, uci + ( UAMpredBGs.length*slopeFromDeviations ) );
            // if slopeFromDeviations is too flat, predicted deviation impact drops linearly from
            // current deviation down to zero over 3h (data points every 5m)
            predUCImax = Math.max(0, uci * ( 1 - UAMpredBGs.length/Math.max(3*60/5,1) ) );
            //console.error(predUCIslope, predUCImax);
            // predicted CI from UAM is the lesser of CI based on deviationSlope or DIA
            predUCI = Math.min(predUCIslope, predUCImax);
            if(predUCI>0) {
                //console.error(UAMpredBGs.length,slopeFromDeviations, predUCI);
                UAMduration=round((UAMpredBGs.length+1)*5/60,1);
            }
            UAMpredBG = UAMpredBGs[UAMpredBGs.length-1] + predZTBGI + predUCI; // This seems like it doubles up on neg predications? // + predNegativeDev

            COBZTpredBGs.push(COBZTpredBG);
            UAMpredBGs.push(UAMpredBG);

            minPredBG = Math.min(COBZTpredBG, UAMpredBG);
        });
    } catch (e) {
        console.error("Problem with iobArray. Automatic bolus/basal disabled:",e);
    }

    console.error("IOB Deviations:",IOBImpacts);

    // duration (hours) = duration (5m) * 5 / 60 * 2 (to account for linear decay)
    console.error("Carb Impact:",ci,"mg/dL per 5m");
    console.error("UAM Impact:",uci,"mg/dL per 5m; UAM Duration:",UAMduration,"hours");

    rT.predBGs = {};

    rT.predBGs.aCOB = COBZTpredBGs;
    rT.predBGs.UAM = UAMpredBGs;

    rT.COB = meal_data.mealCOB;
    rT.IOB = iob_data.iob;

    rT.reason = "COB: " + meal_data.mealCOB + ", Dev: " + convert_bg(deviation, profile) + ", BGI: " + convert_bg(bgi, profile) + ", ISF: " + convert_bg(sens, profile) + ", CR: " + round(profile.carb_ratio, 2) + ", Target: " + convert_bg(target_bg, profile);
    // + ", minPredBG " + convert_bg(minPredBG, profile);

    // We don't want to go below target_bg unless:
    // a) we have just eaten carbs - give a 60 minute window in which anywhere above threshold is good to give more room to deal with spikes
    // b) we are already below target - allow a bit of space to let some insulin in if it will be needed later
    var targetBGs = [];
    for (var i = 0; i < profile.split_bolus_insulin_activity_curve.length; i++) {
        var targetBG = target_bg;
        // Target threshold for 60 minutes after significant carbs
        // Currently significant means more than 15g carbs
        if ( i*5 < (60 - lastSignificantCarbAge) ) {
            targetBG = Math.min(targetBG, threshold);
        }

        // In the first hour
        if (i < 12) {
            if (bg < target_bg) {
                targetBG -= (target_bg - threshold) * 0.1; // At least 10% margin
                // Allow a one hour slope from current BG to target
                targetBG = Math.min(targetBG, bg + ((target_bg - bg)*(i/12)));
            }
            // Allow a little bit of wiggle room in the first hour
            targetBG = targetBG - 2;
        }
        // Never let target drop below threshold
        targetBG = Math.max(targetBG, threshold);
        targetBGs.push(targetBG);
    }
    console.log('targetBGs:',targetBGs);

    rT.predCIs = predicatedCIs;

    // Pick which prediction curve depending if we have COB
    var predBGs = COBZTpredBGs;
    if (meal_data.mealCOB < 1) {
        predBGs = UAMpredBGs;
        sens = sens * 1.1; // HACK: UAM curve tends to be slow to respond in both directions. Hacking the sens by 10% allows for that
    }

    // Calculate max temp basal that we can give that would not cause us to go below target
    var maxTargetBasal = maxInsulinForPredictedBGs(predBGs, profile.basal_insulin_activity_curve, targetBGs, sens);
    // Negative deviations need a little extra care - indicate something might be going wrong
    if (meal_data.deviation < 0) {
        // Use UAM curve as a saftey check
        maxTargetBasal = Math.min(maxTargetBasal,
            maxInsulinForPredictedBGs(UAMpredBGs, profile.basal_insulin_activity_curve, targetBGs, sens)
            );
    }

    console.log('maxTargetBasal:', maxTargetBasal);

    // Cap max basal rate (based on profile value)
    var maxSafeBasal = tempBasalFunctions.getMaxSafeBasal(profile);
    if (maxTargetBasal > maxSafeBasal) {
        maxTargetBasal = maxSafeBasal;
        rT.reason += "; Basal capped at "+round(maxTargetBasal,1)+" due to profile max_basal";
    }

    // Calculate max bolus + minSMBBasalPct temp that we can give that would not cause us to go below target
    // split_bolus_insulin_activity_curve splits the bolus into 'x' equal parts

    var minSMBBasalPct = profile.minSMBBasalPct;

    var basalMargin = Math.min(maxSafeBasal, basal*(minSMBBasalPct/100));
    var basalMarginBGs = [];
    for (var i=0; i<predBGs.length && i < profile.basal_insulin_activity_curve.length; i++) {
        var predictedBasalBG = predBGs[i] - (profile.basal_insulin_activity_curve[i] * sens * basalMargin);
        basalMarginBGs.push(predictedBasalBG);
    }

    var maxTargetBolus = maxInsulinForPredictedBGs(basalMarginBGs, profile.split_bolus_insulin_activity_curve, targetBGs, sens);
    // Negative deviations need a little extra care
    if (meal_data.deviation < 0) {
        maxTargetBolus = Math.min(maxTargetBolus,
          maxInsulinForPredictedBGs(UAMpredBGs, profile.split_bolus_insulin_activity_curve, targetBGs, sens)
          );
    }
    console.log('maxTargetBolus:', maxTargetBolus);

    // Apply max iob constraints (based on profile value)
    if (maxTargetBolus > profile.max_iob - iob_data.iob) {
        maxTargetBolus = profile.max_iob - iob_data.iob;
        rT.reason += "; Bolus capped at "+round(maxTargetBolus,1)+" due to max_iob";
    }

    // Score the two outcomes to see which is better
    // Longer periods will tend to push us towards basal, shorter towards bolus
    var scoreTicks = 2 * (60/5); // Time period to evaluate over

    var predictedBasalBGs = [];
    var predictedBasalBGScore = 0;
    for (var i=0; i<predBGs.length && i < profile.basal_insulin_activity_curve.length; i++) {
        var predictedBasalBG = predBGs[i] - (profile.basal_insulin_activity_curve[i] * sens * maxTargetBasal);
        predictedBasalBGs.push(predictedBasalBG);
        if (i < scoreTicks) {
            predictedBasalBGScore += Math.pow(predictedBasalBG - target_bg,2);
        }
    }
    predictedBasalBGScore = predictedBasalBGScore / scoreTicks;
    console.log('predictedBasalBGScore:',predictedBasalBGScore);

    var predictedBolusBGs = [];
    var predictedBolusBGScore = 0;
    for (var i=0; i<basalMarginBGs.length && i < profile.split_bolus_insulin_activity_curve.length; i++) {
        var predictedBolusBG = basalMarginBGs[i] - (profile.split_bolus_insulin_activity_curve[i] * sens * maxTargetBolus);
        predictedBolusBGs.push(predictedBolusBG);
        if (i < scoreTicks) {
            predictedBolusBGScore += Math.pow(predictedBolusBG - target_bg,2);
        }
    }
    predictedBolusBGScore = predictedBolusBGScore / scoreTicks;
    console.log('predictedBolusBGScore:',predictedBolusBGScore);

    // Calculate the earliest time it is definitely safe to turn back on standard basal
    var bolusSuspendEndTicks = scoreTicks;
    var targetMargin = 5; // 1 is probably enough, 5 is a big safety margin
    for (var i=predictedBolusBGs.length - 1; i > 0; i--) {
        // As soon as we are below target stop
        if (predictedBolusBGs[i] <= (targetBGs[i]+targetMargin)) {
            bolusSuspendEndTicks = i;
            break;
        }
    }
    // Safety check on above - if we don't detect a correct place to turn back on basal then default
    if (i <= 5) {
        bolusSuspendEndTicks = scoreTicks;
    }
    // Round into 15 min chunks (will work with combo)
    // TODO: need to know pump capabilities
    bolusSuspendEndTicks = Math.ceil(bolusSuspendEndTicks / 3) * 3;
    console.log('bolusSuspendEndTicks', bolusSuspendEndTicks);

    // Block too frequent bolusing (can be dangerous - not enough time to see the outcome)
    var lastBolusAge = round(( new Date().getTime() - iob_data.lastBolusTime ) / 60000,1);
    if (lastBolusAge < 4) {
        rT.reason += "; Bolus blocked for "+round(5-lastBolusAge,1)+" minutes";
        predictedBolusBGScore = predictedBasalBGScore + 1;
    }

    // Block boluses that are too small to allow for pump constraints
    // TODO: need to know pump capabilities
    if (maxTargetBolus < 0.2) {
        rT.reason += "; Bolus too small ("+maxTargetBolus+")";
        predictedBolusBGScore = predictedBasalBGScore + 1;
    }

    // Block if SMB is not enabled
    if (!enableSMB) {
        rT.reason += "; SMB Disabled";
        predictedBolusBGScore = predictedBasalBGScore + 1;
    }

     rT.reason += "; Bolus = "+round(predictedBolusBGScore)+", Basal = "+round(predictedBasalBGScore);

    // Apply the better outcome
    if (predictedBasalBGScore <= predictedBolusBGScore) {
        maxTargetBasal = Math.floor(maxTargetBasal * 10) / 10;
        console.log('USING BASAL:',maxTargetBasal);
        rT.reason += "; Basal "+maxTargetBasal+" U/h.";
        rT = tempBasalFunctions.setTempBasal(maxTargetBasal, scoreTicks * 5, profile, rT, currenttemp);
        rT.predBGs.IOB = predictedBasalBGs;
    } else {
        maxTargetBolus = Math.floor(maxTargetBolus * 10) / 10;
        console.log('USING BOLUS:',maxTargetBolus);
        rT.units = maxTargetBolus;
        rT.reason += "; Super bolusing " + rT.units + "U.";
        rT = tempBasalFunctions.setTempBasal(basalMargin, bolusSuspendEndTicks * 5, profile, rT, currenttemp);
        rT.predBGs.IOB = predictedBolusBGs;
    }

    // Time until would drop below threshold
    var minutesAboveThreshold = 240;
    var minPredBG = 999;
    for (var i=0; i < rT.predBGs.IOB.length; i++) {
        // As soon as we are below target stop
        if (rT.predBGs.IOB[i] < threshold) {
            minutesAboveThreshold = Math.min(minutesAboveThreshold, 5*i);
        }
        minPredBG = Math.min(minPredBG, rT.predBGs.IOB[i]);
    }

    // If we are going below threshold how many carbs do we need to fix that
    var carbsReq = round((threshold - minPredBG) / csf);
    if ( carbsReq >= profile.carbsReqThreshold && minutesAboveThreshold <= 45 ) {
        rT.carbsReq = carbsReq;
        rT.carbsReqWithin = minutesAboveThreshold;
        rT.reason += "; "+carbsReq + " add'l carbs req w/in " + minutesAboveThreshold + "m";
    }

    // Clean up the predicated BG for display
    for (var setName in rT.predBGs) {
        rT.predBGs[setName].forEach(function(p, i, theArray) {
            theArray[i] = round(Math.min(401,Math.max(39,p)));
        });
    }

    return rT;
};

module.exports = determine_basal;
