package info.nightscout.androidaps.plugins.general.automation.triggers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.widget.LinearLayout;

import androidx.fragment.app.FragmentManager;

import com.google.common.base.Optional;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.general.automation.elements.ComparatorExists;
import info.nightscout.androidaps.plugins.general.automation.elements.InputString;
import info.nightscout.androidaps.plugins.general.automation.elements.LayoutBuilder;
import info.nightscout.androidaps.plugins.general.automation.elements.StaticLabel;
import info.nightscout.androidaps.utils.DateUtil;
import info.nightscout.androidaps.utils.JsonHelper;
import info.nightscout.androidaps.utils.T;

public class TriggerBTDevice extends Trigger {
    private static Logger log = LoggerFactory.getLogger(L.AUTOMATION);
    InputString deviceName = new InputString();
    private ComparatorExists comparator = new ComparatorExists();

    public TriggerBTDevice() {
        super();
    }

    private TriggerBTDevice(TriggerBTDevice TriggerBTDevice) {
        super();
        comparator = new ComparatorExists(TriggerBTDevice.comparator);
        lastRun = TriggerBTDevice.lastRun;
    }

    public ComparatorExists getComparator() {
        return comparator;
    }

    @Override
    public synchronized boolean shouldRun() {

        if (lastRun > DateUtil.now() - T.mins(5).msecs())
            return false;

        if (devicePaired(deviceName.getValue()) && comparator.getValue() == ComparatorExists.Compare.EXISTS) {
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
            o.put("type", TriggerBTDevice.class.getName());
            JSONObject data = new JSONObject();
            data.put("lastRun", lastRun);
            data.put("comparator", comparator.getValue().toString());
            data.put("name", deviceName.getValue());

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
            deviceName.setValue(JsonHelper.safeGetString(d, "name"));
            comparator.setValue(ComparatorExists.Compare.valueOf(JsonHelper.safeGetString(d, "comparator")));
        } catch (Exception e) {
            log.error("Unhandled exception", e);
        }
        return this;
    }

    @Override
    public int friendlyName() {
        return R.string.btdevice;
    }

    @Override
    public String friendlyDescription() {
        return MainApp.gs(R.string.btdevicecompared, MainApp.gs(comparator.getValue().getStringRes()));
    }

    @Override
    public Optional<Integer> icon() {
        return Optional.of(R.drawable.ic_keyboard_tab);
    }

    @Override
    public Trigger duplicate() {
        return new TriggerBTDevice(this);
    }

    TriggerBTDevice lastRun(long lastRun) {
        this.lastRun = lastRun;
        return this;
    }

    public TriggerBTDevice comparator(ComparatorExists.Compare compare) {
        this.comparator = new ComparatorExists().setValue(compare);
        return this;
    }

    @Override
    public void generateDialog(LinearLayout root, FragmentManager fragmentManager) {
        new LayoutBuilder()
                .add(new StaticLabel(R.string.btdevice))
                .add(deviceName)
                .add(comparator)
                .build(root);
    }

    public boolean devicePaired(String name){
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBluetoothAdapter == null)
            return false;
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        boolean isBonded = false;
        List<String> s = new ArrayList<String>();
        for(BluetoothDevice bt : pairedDevices) {
            s.add(bt.getName());
            log.debug("Device name is :"+bt.getName() + " bond state " + bt.getBondState());
            if (bt.getName().equals(name))
                isBonded = true;
        }
        return isBonded;
    }

}
