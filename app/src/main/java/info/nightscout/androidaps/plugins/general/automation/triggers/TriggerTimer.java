package info.nightscout.androidaps.plugins.general.automation.triggers;

import android.widget.LinearLayout;

import androidx.fragment.app.FragmentManager;

import com.google.common.base.Optional;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.TempTarget;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.general.automation.AutomationPlugin;
import info.nightscout.androidaps.plugins.general.automation.actions.ActionStartTimer;
import info.nightscout.androidaps.plugins.general.automation.elements.Comparator;
import info.nightscout.androidaps.plugins.general.automation.elements.ComparatorExists;
import info.nightscout.androidaps.plugins.general.automation.elements.InputDuration;
import info.nightscout.androidaps.plugins.general.automation.elements.InputString;
import info.nightscout.androidaps.plugins.general.automation.elements.LabelWithElement;
import info.nightscout.androidaps.plugins.general.automation.elements.LayoutBuilder;
import info.nightscout.androidaps.plugins.general.automation.elements.StaticLabel;
import info.nightscout.androidaps.plugins.general.automation.elements.Dropdown;
import info.nightscout.androidaps.plugins.treatments.TreatmentsPlugin;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.JsonHelper;
import info.nightscout.androidaps.utils.T;

public class TriggerTimer extends Trigger {
    private static Logger log = LoggerFactory.getLogger(L.AUTOMATION);

    private Comparator comparator = new Comparator();
    private InputString timerName = new InputString();
    private InputDuration timerDuration = new InputDuration(0, InputDuration.TimeUnit.MINUTES);
    private Dropdown timersDropdown;

    public TriggerTimer() {
        super();
    }

    private TriggerTimer(TriggerTimer triggerTimer) {
        super();
        timerName = triggerTimer.timerName;
        timerDuration = new InputDuration(timerDuration.getMinutes(), InputDuration.TimeUnit.MINUTES);
        comparator = new Comparator(triggerTimer.comparator);
        lastRun = triggerTimer.lastRun;
    }

    public Comparator getComparator() {
        return comparator;
    }

    @Override
    public synchronized boolean shouldRun() {

        if (lastRun > DateUtil.now() - T.mins(1).msecs())
            return false;

        log.debug("timerDuration is "+ timerDuration.getMinutes());
        log.debug("timerName is "+ timerName.getValue());

        boolean doRun = (comparator.getValue().check((timerDuration.getMinutes() * 60 * 1000L), System.currentTimeMillis()));
        if (doRun) {
            if (L.isEnabled(L.AUTOMATION))
                log.debug("Ready for execution: " + friendlyDescription());
            return true;
        }
        return false;
    }

    @Override
    public synchronized String toJSON() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", TriggerTimer.class.getName());
            JSONObject data = new JSONObject();
            data.put("lastRun", lastRun);
            data.put("comparator", comparator.getValue().toString());
            o.put("data", data);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return o.toString();
    }

    @Override
    Trigger fromJSON(String data) {
        try {
            JSONObject d = new JSONObject(data);
            lastRun = JsonHelper.safeGetLong(d, "lastRun");
            comparator.setValue(Comparator.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")));
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
        return this;
    }

    @Override
    public int friendlyName() {
        return R.string.timer_friendly_description;
    }

    @Override
    public String friendlyDescription() {
        return MainApp.gs(R.string.timer_friendly_name, timerName.getValue(), MainApp.gs(comparator.getValue().getStringRes()));
    }

    @Override
    public Optional<Integer> icon() {
        return Optional.of(R.drawable.ic_keyboard_tab);
    }

    @Override
    public Trigger duplicate() {
        return new TriggerTimer(this);
    }

    TriggerTimer lastRun(long lastRun) {
        this.lastRun = lastRun;
        return this;
    }

    @Override
    public void generateDialog(LinearLayout root, FragmentManager fragmentManager) {
        ArrayList<String> timerNames = AutomationPlugin.INSTANCE.getTimers();
        if (timerNames.size() > 0){
            timersDropdown = new Dropdown(timerNames);
            new LayoutBuilder()
                    .add(new StaticLabel(R.string.careportal_temporarytarget))
                    .add(new LabelWithElement(MainApp.gs(R.string.trigger_timer_name), "", timersDropdown))
                    .add(comparator)
                    .add(new LabelWithElement(MainApp.gs(R.string.unit_minutes), "", timerDuration))
                    .build(root);
        } else {
            new LayoutBuilder()
                    .add(new StaticLabel(R.string.timer_message))
                    .add(new LabelWithElement(MainApp.gs(R.string.trigger_timer_name) + ": ", "", timerName))
                    .add(comparator)
                    .add(new LabelWithElement(MainApp.gs(R.string.unit_minutes), "", timerDuration))
                    .build(root);
        }
    }

}
