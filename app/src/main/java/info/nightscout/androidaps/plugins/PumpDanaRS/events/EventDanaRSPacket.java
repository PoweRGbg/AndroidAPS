package info.nightscout.androidaps.plugins.PumpDanaRS.events;

import info.nightscout.androidaps.events.Event;
import info.nightscout.androidaps.plugins.PumpDanaRS.comm.DanaRS_Packet;

/**
 * Created by mike on 01.09.2017.
 */

public class EventDanaRSPacket extends Event{
    public EventDanaRSPacket(DanaRS_Packet data) {
        this.data = data;
    }

    public DanaRS_Packet data;
}
