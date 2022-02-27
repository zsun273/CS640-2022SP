package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.Map;
import java.util.HashMap;
import java.lang.System;
import java.util.List;
import java.util.ArrayList;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{
    Map<MACAddress, SwitchEntry> SwitchMap = new HashMap<MACAddress, SwitchEntry>();

    /**
     * @author Zhuocheng Sun
     */
    static class SwitchEntry {
        private MACAddress destination;
        private Iface inIface;			// the interface on which the packet was received
        private long TTL;						// create time

        public SwitchEntry(MACAddress destination, Iface inIface) {
            this.destination = destination;
            this.inIface = inIface;
            this.TTL = System.currentTimeMillis();
        }

        public Iface getInIface() {
            return inIface;
        }

        public long getTTL() {
            return TTL;
        }
    }

    /**
     * Creates a router for a specific host.
     * @param host hostname for the router
     */
    public Switch(String host, DumpFile logfile)
    {
        super(host,logfile);
    }

    /**
     * Handle an Ethernet packet received on a specific interface.
     * @param etherPacket the Ethernet packet that was received
     * @param inIface the interface on which the packet was received
     */
    public void handlePacket(Ethernet etherPacket, Iface inIface)
    {
        System.out.println("*** -> Received packet: " +
                etherPacket.toString().replace("\n", "\n\t"));

        /********************************************************************/
        MACAddress sourceMAC = etherPacket.getSourceMAC();
        MACAddress destinationMAC = etherPacket.getDestinationMAC();
        List<MACAddress> timedOutMAC = new ArrayList<MACAddress>();

        SwitchMap.put(sourceMAC, new SwitchEntry(sourceMAC, inIface));
        // find timed out entries and delete them from the switch map
        for (Map.Entry<MACAddress, SwitchEntry> entry: SwitchMap.entrySet()){
            if (System.currentTimeMillis() - entry.getValue().getTTL() >= 15000) {
                timedOutMAC.add(entry.getKey());
            }
        }
        for (int i = 0; i < timedOutMAC.size(); i++){
            SwitchMap.remove(timedOutMAC.get(i));
        }
        timedOutMAC.clear();

        System.out.println("\n----Current entries in switch map-------\n");
        // print out all the entries in the switch map
        for (Map.Entry<MACAddress, SwitchEntry> entry: SwitchMap.entrySet()){
            System.out.println("MAC: " + entry.getKey() + " Interface: " + entry.getValue().getInIface()
                    + " Exist Time: " + (System.currentTimeMillis() - entry.getValue().getTTL()));
        }
        System.out.println("-------------------------------------------");

        if (SwitchMap.containsKey(destinationMAC)) {
            System.out.println("\nDestination is in the Map");
            System.out.println("Sending: " + destinationMAC + " Interface: " + SwitchMap.get(destinationMAC).getInIface());
            if (this.sendPacket(etherPacket, SwitchMap.get(destinationMAC).getInIface()) == false){
                System.out.println("Send failed\n");
            }
            return;
        }
        else{
            for (Map.Entry<String, Iface> entry : this.interfaces.entrySet()){
                if (entry.getValue().equals(inIface)) continue;
                System.out.println("Sending: " + destinationMAC + " Interface: " + entry.getValue());
                if(this.sendPacket(etherPacket, entry.getValue())){
                    System.out.println("Send successful\n");
                }
            }
            return;
        }
        /********************************************************************/
    }
}