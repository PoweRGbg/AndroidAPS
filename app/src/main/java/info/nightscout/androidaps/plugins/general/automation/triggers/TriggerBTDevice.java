package info.nightscout.androidaps.plugins.general.automation.triggers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
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

    private InputString deviceName = new InputString();
    private ComparatorExists comparator = new ComparatorExists();
    boolean connectedToDevice = false;
    public TriggerBTDevice() {
        super();
    }

    private TriggerBTDevice(TriggerBTDevice TriggerBTDevice) {
        super();
        deviceName.setValue(TriggerBTDevice.deviceName.getValue());
        comparator = new ComparatorExists(TriggerBTDevice.comparator);
        lastRun = TriggerBTDevice.lastRun;
    }

    public ComparatorExists getComparator() {
        return comparator;
    }

    @Override
    public synchronized boolean shouldRun() {
        connectedToDevice = false;
        checkConnected();
        log.debug("Connected "+connectedToDevice+" left "+ T.msecs(DateUtil.now()-lastRun).mins());
        if (lastRun > DateUtil.now() - T.mins(5).msecs())
            return false;

        if (connectedToDevice && comparator.getValue() == ComparatorExists.Compare.EXISTS) {
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

    // Get the list of paired BT devices to use in dropdown menu
    public List<String> devicePaired(String name){
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        List<String> s = new ArrayList<String>();
        if (mBluetoothAdapter == null)
            return s;
        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();

        for(BluetoothDevice bt : pairedDevices) {
            s.add(bt.getName());
//            log.debug("Device name is: "+bt.getName());
        }
        return s;
    }

    public void checkConnected() {
        connectedToDevice = false; // reset connection status
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBluetoothAdapter == null)
            return; // No BT enabled or available

        int state = BluetoothAdapter.getDefaultAdapter().getProfileConnectionState(BluetoothProfile.HEADSET);
        if (state != BluetoothProfile.STATE_CONNECTED) {
            return;
        }
        try
        {
            Context context = MainApp.instance().getApplicationContext();
            log.debug("Connected before "+connectedToDevice);
            BluetoothAdapter.getDefaultAdapter().getProfileProxy(context, serviceListener, BluetoothProfile.STATE_CONNECTED);
            log.debug("Connected after "+connectedToDevice);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private BluetoothProfile.ServiceListener serviceListener = new BluetoothProfile.ServiceListener()
    {
        @Override
        public void onServiceDisconnected(int profile)
        { }

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy)
        {

            for (BluetoothDevice device : proxy.getConnectedDevices())
            {
                log.debug("onServiceConnected" + "|" + device.getName() + "| " + device.getAddress() + " | " + proxy.getConnectionState(device) + "(connected = "
                        + BluetoothProfile.STATE_CONNECTED +") | Profile "+profile+"| Proxy "+proxy);
                log.debug("Required: " +deviceName.getValue());
                log.debug("Names match: " +(deviceName.getValue().equals(device.getName())));
                connectedToDevice = deviceName.getValue().equals(device.getName());
            }

            BluetoothAdapter.getDefaultAdapter().closeProfileProxy(profile, proxy);
        }
    };

}
