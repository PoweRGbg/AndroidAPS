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
import java.util.Map;
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

public interface ActiveCarb {

    boolean startedAt(long atTime);

    boolean expiredAt(long atTime);

    void setCarbsAt(long atTime, double value);

    double addCarbsAt(long atTime, double value, boolean isDiscard);

    long timeRemaining(long atTime);

    void outputHistory();

    double getCarbsRemaining();

    double getPredictedCarbsConfidence();

    double get5minImpact(); // Legacy support

    List<Double> getPredicatedCarbs(int numberOfDataPoints);

    void calculateCarbsForFeedbackLoop();

    double carbsPredictionErrorFromHistory();

    ActiveCarb clone();
}
