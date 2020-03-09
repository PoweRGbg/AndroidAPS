package info.nightscout.androidaps.plugins.general.automation.actions;

import android.widget.LinearLayout;

import com.google.common.base.Optional;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.PumpEnactResult;
import info.nightscout.androidaps.plugins.general.automation.AutomationPlugin;
import info.nightscout.androidaps.plugins.general.automation.elements.ComparatorExists;
import info.nightscout.androidaps.plugins.general.automation.elements.InputDuration;
import info.nightscout.androidaps.plugins.general.automation.elements.InputString;
import info.nightscout.androidaps.plugins.general.automation.elements.LabelWithElement;
import info.nightscout.androidaps.plugins.general.automation.elements.LayoutBuilder;
import info.nightscout.androidaps.plugins.general.automation.triggers.TriggerTimer;
import info.nightscout.androidaps.queue.Callback;
import info.nightscout.androidaps.utils.JsonHelper;

public class ActionStartTimer extends Action {
    private static final Logger log = LoggerFactory.getLogger(ActionNotification.class);

    public InputString timerName = new InputString();
    InputDuration timerDuration = new InputDuration(0, InputDuration.TimeUnit.MINUTES);
    Long timerStartedAt = 0l;

    @Override
    public int friendlyName() {
        return R.string.timer_message;
    }

    public int friendlyDescription() {
        return R.string.timer_message;
    }

    @Override
    public String shortDescription() {
        return MainApp.gs(R.string.timer_message_name, timerName.getValue());
    }

    @Override
    public void doAction(Callback callback) {
        ActionStartTimer timer = AutomationPlugin.INSTANCE.getTimer(timerName.getValue());
        long whenWillExpire = this.timerStartedAt.longValue()+(this.timerDuration.getMinutes()*60*1000L);
        log.debug("Timer to start is named "+timerName.getValue());
        log.debug("Started : "+new Date(this.timerStartedAt).toString());
        log.debug("Duration : "+this.timerDuration.getMinutes());
        log.debug("Timed out: " + (whenWillExpire < System.currentTimeMillis()));
        if(timer.timerName.getValue() == this.timerName.getValue()){
            log.debug("Timer exists!");
            return;
        }

        timerStartedAt = System.currentTimeMillis();
        if (callback != null)
            callback.result(new PumpEnactResult().success(true).comment(R.string.ok)).run();

    }

    @Override
    public Optional<Integer> icon() {
        return Optional.of(R.drawable.icon_actions_startextbolus);
    }

    @Override
    public String toJSON() {
        JSONObject o = new JSONObject();
        JSONObject data = new JSONObject();
        try {
            data.put("name", timerName.getValue());
            data.put("duration", timerDuration.getValue());
            data.put("startedAt", timerStartedAt);
            o.put("type", this.getClass().getName());
            o.put("data", data);
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return o.toString();
    }

    @Override
    public Action fromJSON(String data) {
        try {
            JSONObject o = new JSONObject(data);
            String timerNameString = JsonHelper.safeGetString(o, "name");
            if (timerNameString == "")
                timerNameString = MainApp.gs(R.string.name_your_timer);
            timerName.setValue(timerNameString);
            timerDuration.setMinutes(JsonHelper.safeGetInt(o, "duration"));
            timerStartedAt = JsonHelper.safeGetLong(o, "startedAt");
        } catch (JSONException e) {
            log.error("Unhandled exception", e);
        }
        return this;
    }

    @Override
    public boolean hasDialog() {
        return true;
    }

    @Override
    public void generateDialog(LinearLayout root) {
        new LayoutBuilder()
                .add(new LabelWithElement(MainApp.gs(R.string.trigger_timer_name), "", timerName))
                .add(new LabelWithElement(MainApp.gs(R.string.careportal_newnstreatment_duration_min_label), "", timerDuration))
                .build(root);
    }

}
