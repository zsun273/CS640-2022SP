package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.*;
import java.lang.System;

/**
 * @author Aaron Gember-Jacobson
 */
public class Switch extends Device
{
    Map<MACAddress, SwitchEntry> SwitchMap;
    Timer timer;

    /**
     * @author Zhuocheng Sun
     */
    static class SwitchEntry {
        private MACAddress destination;
        private Iface inIface;			// the interface on which the packet was received
        private int TTL;						// create time

        public SwitchEntry(MACAddress destination, Iface inIface) {
            this.destination = destination;
            this.inIface = inIface;
            this.TTL = 15;
        }

        public Iface getInIface() {
            return inIface;
        }

        public int getTTL() {
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
        this.SwitchMap = new HashMap<MACAddress, SwitchEntry>();
        this.timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                for (MACAddress key : SwitchMap.keySet()) {
                    SwitchEntry se = SwitchMap.get(key);
                    se.TTL -= 1;
                    if (se.TTL < 0)
                        SwitchMap.remove(key);
                    else
                        SwitchMap.replace(key, se);
                }
            }
        }, 0, 1000);
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

        SwitchMap.put(sourceMAC, new SwitchEntry(sourceMAC, inIface));

        System.out.println("\n----Current entries in switch map-------\n");
        // print out all the entries in the switch map
        for (Map.Entry<MACAddress, SwitchEntry> entry: SwitchMap.entrySet()){
            System.out.println("MAC: " + entry.getKey() + " Interface: " + entry.getValue().getInIface()
                    + " Exist Time: " + entry.getValue().getTTL());
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