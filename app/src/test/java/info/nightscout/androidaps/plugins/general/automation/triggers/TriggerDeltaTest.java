package info.nightscout.androidaps.plugins.general.automation.triggers;

import com.google.common.base.Optional;
import com.squareup.otto.Bus;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.ArrayList;
import java.util.List;

import info.AAPSMocker;
import info.nightscout.androidaps.Constants;
import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.plugins.configBuilder.ProfileFunctions;
import info.nightscout.androidaps.plugins.general.automation.elements.Comparator;
import info.nightscout.androidaps.plugins.general.nsclient.data.NSSgv;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.GlucoseStatus;
import info.nightscout.androidaps.plugins.iob.iobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.utils.DateUtil;

import static org.powermock.api.mockito.PowerMockito.when;

@RunWith(PowerMockRunner.class)
@PrepareForTest({MainApp.class, Bus.class, ProfileFunctions.class, DateUtil.class, IobCobCalculatorPlugin.class, GlucoseStatus.class})
public class TriggerDeltaTest {

    long now = 1514766900000L;

    @Test
    public void shouldRunTest() {
        when(IobCobCalculatorPlugin.getPlugin().getBgReadings()).thenReturn(generateValidBgData());

        TriggerDelta t = new TriggerDelta().setUnits(Constants.MGDL).setValue(73d).comparator(Comparator.Compare.IS_EQUAL);
        Assert.assertFalse(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MGDL).setValue(-2d).comparator(Comparator.Compare.IS_EQUAL);
        Assert.assertTrue(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MGDL).setValue(-3d).comparator(Comparator.Compare.IS_EQUAL_OR_GREATER);
        Assert.assertTrue(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MGDL).setValue(2d).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER);
        Assert.assertTrue(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MGDL).setValue(2d).comparator(Comparator.Compare.IS_EQUAL);
        Assert.assertFalse(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MMOL).setValue(0.3d).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER);
        Assert.assertTrue(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MMOL).setValue(0.1d).comparator(Comparator.Compare.IS_EQUAL_OR_GREATER);
        Assert.assertFalse(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MMOL).setValue(-0.5d).comparator(Comparator.Compare.IS_EQUAL_OR_GREATER);
        Assert.assertTrue(t.shouldRun());
        t = new TriggerDelta().setUnits(Constants.MMOL).setValue(-0.2d).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER);
        Assert.assertFalse(t.shouldRun());

        when(IobCobCalculatorPlugin.getPlugin().getBgReadings()).thenReturn(new ArrayList<>());
        t = new TriggerDelta().setUnits(Constants.MGDL).setValue(213).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER);
        Assert.assertFalse(t.shouldRun());
        t = new TriggerDelta().comparator(Comparator.Compare.IS_NOT_AVAILABLE);
        Assert.assertTrue(t.shouldRun());

        t = new TriggerDelta().setUnits(Constants.MGDL).setValue(214).comparator(Comparator.Compare.IS_EQUAL).lastRun(now - 1);
        Assert.assertFalse(t.shouldRun());

    }

    @Test
    public void copyConstructorTest() {
        TriggerDelta t = new TriggerDelta().setUnits(Constants.MGDL).setValue(213).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER);
        TriggerDelta t1 = (TriggerDelta) t.duplicate();
        Assert.assertEquals(213d, t1.getValue(), 0.01d);
        Assert.assertEquals(Constants.MGDL, t1.getUnits());
        Assert.assertEquals(Comparator.Compare.IS_EQUAL_OR_LESSER, t.getComparator().getValue());
    }

    @Test
    public void executeTest() {
        TriggerDelta t = new TriggerDelta().setUnits(Constants.MGDL).setValue(213).comparator(Comparator.Compare.IS_EQUAL_OR_LESSER);
        t.executed(1);
        Assert.assertEquals(1l, t.getLastRun());
    }

    String deltaJson = "{\"data\":{\"comparator\":\"IS_EQUAL\",\"lastRun\":0,\"units\":\"mmol\",\"type\":0,\"value\":4.1},\"type\":\"info.nightscout.androidaps.plugins.general.automation.triggers.TriggerDelta\"}";

    @Test
    public void toJSONTest() {
        TriggerDelta t = new TriggerDelta().setUnits(Constants.MMOL).setValue(4.1d).comparator(Comparator.Compare.IS_EQUAL);
        Assert.assertEquals(deltaJson, t.toJSON());
    }

    @Test
    public void fromJSONTest() throws JSONException {
        TriggerDelta t = new TriggerDelta().setUnits(Constants.MMOL).setValue(4.1d).comparator(Comparator.Compare.IS_EQUAL);

        TriggerDelta t2 = (TriggerDelta) Trigger.instantiate(new JSONObject(t.toJSON()));
        Assert.assertEquals(Comparator.Compare.IS_EQUAL, t2.getComparator().getValue());
        Assert.assertEquals(4.1d, t2.getValue(), 0.01d);
        Assert.assertEquals(Constants.MMOL, t2.getUnits());
        Assert.assertEquals(0d, t2.getType(), 0.01d);
    }

    @Test
    public void iconTest() {
        Assert.assertEquals(Optional.of(R.drawable.as), new TriggerDelta().icon());
    }

    @Test
    public void typeToStringTest() {
        TriggerDelta t = new TriggerDelta();
        Assert.assertEquals(MainApp.gs(R.string.delta), t.typeToString(0));
        Assert.assertEquals(MainApp.gs(R.string.short_avgdelta), t.typeToString(1));
        Assert.assertEquals(MainApp.gs(R.string.long_avgdelta), t.typeToString(2));
    }


    @Before
    public void mock() {
        AAPSMocker.mockMainApp();
        AAPSMocker.mockBus();
        AAPSMocker.mockIobCobCalculatorPlugin();
        AAPSMocker.mockProfileFunctions();
        AAPSMocker.mockApplicationContext();

        PowerMockito.mockStatic(DateUtil.class);
        when(DateUtil.now()).thenReturn(now);

    }

    List<BgReading> generateValidBgData() {
        List<BgReading> list = new ArrayList<>();
        try {
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":214,\"mills\":1514766900000,\"direction\":\"Flat\"}"))));
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":216,\"mills\":1514766600000,\"direction\":\"Flat\"}")))); // +2
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":219,\"mills\":1514766300000,\"direction\":\"Flat\"}")))); // +3
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":223,\"mills\":1514766000000,\"direction\":\"Flat\"}")))); // +4
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":222,\"mills\":1514765700000,\"direction\":\"Flat\"}"))));
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":224,\"mills\":1514765400000,\"direction\":\"Flat\"}"))));
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":226,\"mills\":1514765100000,\"direction\":\"Flat\"}"))));
            list.add(new BgReading(new NSSgv(new JSONObject("{\"mgdl\":228,\"mills\":1514764800000,\"direction\":\"Flat\"}"))));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return list;
    }
}