package info.nightscout.androidaps.plugins.PumpDanaRS.comm;

import com.cozmo.danar.util.BleCommandUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

import info.nightscout.androidaps.logging.L;
import info.nightscout.androidaps.plugins.PumpDanaR.DanaRPump;

public class DanaRS_Packet_Bolus_Get_Step_Bolus_Information extends DanaRS_Packet {
    private Logger log = LoggerFactory.getLogger(L.PUMPCOMM);

    public DanaRS_Packet_Bolus_Get_Step_Bolus_Information() {
        super();
        opCode = BleCommandUtil.DANAR_PACKET__OPCODE_BOLUS__GET_STEP_BOLUS_INFORMATION;
        if (L.isEnabled(L.PUMPCOMM))
            log.debug("New message");
    }

    @Override
    public void handleMessage(byte[] data) {
        DanaRPump pump = DanaRPump.getInstance();

        int dataIndex = DATA_START;
        int dataSize = 1;
        int error = byteArrayToInt(getBytes(data, dataIndex, dataSize));

        dataIndex += dataSize;
        dataSize = 1;
        int bolusType = byteArrayToInt(getBytes(data, dataIndex, dataSize));

        dataIndex += dataSize;
        dataSize = 2;
        pump.initialBolusAmount = byteArrayToInt(getBytes(data, dataIndex, dataSize)) / 100d;

        pump.lastBolusTime = new Date(); // it doesn't provide day only hour+min, workaround: expecting today
        dataIndex += dataSize;
        dataSize = 1;
        pump.lastBolusTime.setHours(byteArrayToInt(getBytes(data, dataIndex, dataSize)));

        dataIndex += dataSize;
        dataSize = 1;
        pump.lastBolusTime.setMinutes(byteArrayToInt(getBytes(data, dataIndex, dataSize)));

        dataIndex += dataSize;
        dataSize = 2;
        pump.lastBolusAmount = byteArrayToInt(getBytes(data, dataIndex, dataSize)) / 100d;

        dataIndex += dataSize;
        dataSize = 2;
        pump.maxBolus = byteArrayToInt(getBytes(data, dataIndex, dataSize)) / 100d;

        dataIndex += dataSize;
        dataSize = 1;
        pump.bolusStep = byteArrayToInt(getBytes(data, dataIndex, dataSize)) / 100d;

        if (L.isEnabled(L.PUMPCOMM)) {
            log.debug("Result: " + error);
            log.debug("BolusType: " + bolusType);
            log.debug("Initial bolus amount: " + pump.initialBolusAmount + " U");
            log.debug("Last bolus time: " + pump.lastBolusTime.toLocaleString());
            log.debug("Last bolus amount: " + pump.lastBolusAmount);
            log.debug("Max bolus: " + pump.maxBolus + " U");
            log.debug("Bolus step: " + pump.bolusStep + " U");
        }
    }

    @Override
    public String getFriendlyName() {
        return "BOLUS__GET_STEP_BOLUS_INFORMATION";
    }
}
