package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.Map;
import java.util.HashMap;
import java.lang.System;

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
		long TTL;						// time to live

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
		/* TODO: Handle packets                                             */
		MACAddress sourceMAC;
		MACAddress destinationMAC;
		sourceMAC = etherPacket.getSourceMAC();
		destinationMAC = etherPacket.getDestinationMAC();

		SwitchMap.put(sourceMAC, new SwitchEntry(sourceMAC, inIface));
		// find timed out entries and delete them from the switch map
		for (Map.Entry<MACAddress, SwitchEntry> entry: SwitchMap.entrySet()){
			if (System.currentTimeMillis() - entry.getValue().getTTL() >= 15000) {
				SwitchMap.remove(entry.getKey());
			}
		}

		System.out.println("\n Current entries in switch map\n");
		for (Map.Entry<MACAddress, SwitchEntry> entry: SwitchMap.entrySet()){
			System.out.println(entry.getKey() + ": " + entry.getValue().getInIface() + "," + entry.getValue().getTTL());
		}

		if (SwitchMap.containsKey(destinationMAC)) {
			System.out.println("Destination in the Map\n");
			if (this.sendPacket(etherPacket, SwitchMap.get(destinationMAC).getInIface()) == false){
				System.out.println("Send failed");
			}
			return;
		}
		else{
			Map<String,Iface> interfaces = this.interfaces;
			for (Map.Entry<String, Iface> entry : interfaces.entrySet()){
				System.out.println("Sending: " + destinationMAC + ": " + entry.getValue());
				if(this.sendPacket(etherPacket, entry.getValue())){
					System.out.println("Send successful");
				}
			}
			return;
		}
		/********************************************************************/
	}
}
