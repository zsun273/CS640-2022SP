package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.*;

import java.util.Timer;
import java.util.TimerTask;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{	
	/** Routing table for the router */
	private RouteTable routeTable;

	/** ARP cache for the router */
	private ArpCache arpCache;

	/** Timer for RIP response */
	private Timer timer;

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile)
	{
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}

	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable()
	{ return this.routeTable; }

	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile)
	{
		if (!routeTable.load(routeTableFile, this))
		{
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}

		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}

	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile)
	{
		if (!arpCache.load(arpCacheFile))
		{
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}

		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
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

		switch(etherPacket.getEtherType())
		{
		case Ethernet.TYPE_IPv4:
			this.handleIpPacket(etherPacket, inIface);
			break;
		// Ignore all other packet types, for now
		}

		/********************************************************************/
	}

	private void handleIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		System.out.println("Handle IP packet");

		// Verify checksum
		short origCksum = ipPacket.getChecksum();
		ipPacket.resetChecksum();
		byte[] serialized = ipPacket.serialize();
		ipPacket.deserialize(serialized, 0, serialized.length);
		short calcCksum = ipPacket.getChecksum();
		if (origCksum != calcCksum)
		{ return; }

		// Check TTL
		ipPacket.setTtl((byte)(ipPacket.getTtl()-1));
		if (0 == ipPacket.getTtl())
		{
			// generate an ICMP time exceed message here
			sendICMPmsg((byte)11, (byte)0, etherPacket, inIface, ipPacket);
			return;
		}

		// Reset checksum now that TTL is decremented
		ipPacket.resetChecksum();

		// Check if packet is destined for one of router's interfaces
		for (Iface iface : this.interfaces.values())
		{
			if (ipPacket.getDestinationAddress() == iface.getIpAddress())
			{
				// destination port unreachable icmp
				if (ipPacket.getProtocol() == IPv4.PROTOCOL_TCP){
					sendICMPmsg((byte)3, (byte)3, etherPacket, inIface, ipPacket);
					return;
				}
				if (ipPacket.getProtocol() == IPv4.PROTOCOL_UDP){
					UDP udpPacket = (UDP)ipPacket.getPayload();
					if (udpPacket.getDestinationPort() == UDP.RIP_PORT){
						// this packet is RIP requests or responses
						handleRIPpacket(etherPacket, inIface);
					} else{
						sendICMPmsg((byte)3, (byte)3, etherPacket, inIface, ipPacket);
						return;
					}
				}
				if (ipPacket.getProtocol() == IPv4.PROTOCOL_ICMP){
					ICMP icmpPacket = (ICMP)ipPacket.getPayload();
					if (icmpPacket.getIcmpType() == (byte)8){
						// construct and send an echo reply message
						System.out.println("Send an echo reply message here");
						// check if the destination ip of the echo request match any ip of router's interfaces
						// no need to check, already in the outer if statement
						sendICMPmsg((byte)0, (byte)0, etherPacket, inIface, ipPacket);
					} else{
						return;
					}
				}
				return;
			}
		}

		// Do route lookup and forward
		this.forwardIpPacket(etherPacket, inIface);
	}

	private void forwardIpPacket(Ethernet etherPacket, Iface inIface)
	{
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }
		System.out.println("Forward IP packet");

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		int dstAddr = ipPacket.getDestinationAddress();

		// Find matching route table entry 
		RouteEntry bestMatch = this.routeTable.lookup(dstAddr);

		// If no entry matched, do nothing
		if (null == bestMatch)
		{
			// Destination network unreachable ICMP
			sendICMPmsg((byte)3, (byte)0, etherPacket, inIface, ipPacket);
			return;
		}

		// Make sure we don't sent a packet back out the interface it came in
		Iface outIface = bestMatch.getInterface();
		if (outIface == inIface)
		{
			return;
		}

		// Set source MAC address in Ethernet header
		etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

		// If no gateway, then nextHop is IP destination
		int nextHop = bestMatch.getGatewayAddress();
		if (0 == nextHop)
		{ nextHop = dstAddr; }

		// Set destination MAC address in Ethernet header
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
		if (null == arpEntry)
		{
			// destination host unreachable icmp
			sendICMPmsg((byte)3, (byte)1, etherPacket, inIface, ipPacket);
			return;
		}
		etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());

		this.sendPacket(etherPacket, outIface);
	}

	private void sendICMPmsg(byte type, byte code, Ethernet etherPacket, Iface inIface, IPv4 ipPacket)
	{
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		ICMP icmp = new ICMP();
		Data data = new Data();
		ether.setPayload(ip);
		ip.setPayload(icmp);
		icmp.setPayload(data);

		// set up ethernet header
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		int sourceAddress = ipPacket.getSourceAddress();
		RouteEntry bestMatch = this.routeTable.lookup(sourceAddress);

		// If no gateway, then nextHop is IP destination
		int nextHop = bestMatch.getGatewayAddress();
		if (0 == nextHop)
		{ nextHop = sourceAddress; }

		// Set destination MAC address in Ethernet header
		ArpEntry arpEntry = this.arpCache.lookup(nextHop);
		if (null == arpEntry){
			ether.setDestinationMACAddress(etherPacket.getSourceMAC().toBytes());
		} else{
			ether.setDestinationMACAddress(arpEntry.getMac().toBytes());
		}

		// set up ip header
		ip.setTtl((byte)64);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);
		ip.setSourceAddress(inIface.getIpAddress());
		ip.setDestinationAddress(ipPacket.getSourceAddress());

		// set up ICMP header
		icmp.setIcmpType(type);
		icmp.setIcmpCode(code);

		if (type == (byte)0){
			// echo reply icmp
			ip.setSourceAddress(ipPacket.getDestinationAddress());
			ICMP icmpHeaderPayload = (ICMP)ipPacket.getPayload();
			icmpHeaderPayload.setChecksum((short)0);
			byte[] icmpBytes = icmpHeaderPayload.serialize();
			byte[] icmpPayload = new byte[icmpBytes.length - 4];
			for (int i = 4; i < icmpBytes.length; i++){
				icmpPayload[i-4] = icmpBytes[i];
			}
			data.setData(icmpPayload);
		}
		else{
			// set up ICMP payload
			ipPacket.resetChecksum();
			byte[] ipBytes = ipPacket.serialize();
			int nIpBytes = ipPacket.getHeaderLength()*4 + 8; // original header + 8 B of payload
			byte[] icmpPayload = new byte[nIpBytes + 4];
			for (int j = 0; j < 4; j++){
				icmpPayload[j] = (byte) 0;				// fill in padding with 0.
			}
			for (int i = 0; i < nIpBytes; i++) {
				icmpPayload[i+4] = ipBytes[i]; 			// move everything back 4 bytes
			}
			data.setData(icmpPayload);
		}

		sendPacket(ether, inIface);
		System.out.println("ICMP packet sent");
	}

	public void initializeRouteTable() {
		// add entries to route table for subnets that are directly reachable via router's interfaces
		for (Iface iface : this.interfaces.values()){
			int subnetMask = iface.getSubnetMask();
			int destination = iface.getIpAddress() & subnetMask;
			// int destinationAddr, int gatewayAddr, int maskAddr, Iface iface, int cost
			this.routeTable.insert(destination, 0, subnetMask, iface, 1);
		}
		System.out.println("Create static route table");
		System.out.println(this.routeTable.toString());

		// send initial RIP out all of router's interfaces
		for (Iface iface: this.interfaces.values()){
			// RIP Request
			this.sendRIP(iface, true, true);
		}

		this.timer = new Timer();
		this.timer.scheduleAtFixedRate(new updateRIP(), 1000, 1000);
	}

	public void sendRIP(Iface inIface, boolean broadcast, boolean request){

	}

	public void handleRIPpacket(Ethernet etherPacket, Iface inIface){
		// Make sure it's an IP packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4)
		{ return; }

		// Get IP header
		IPv4 ipPacket = (IPv4)etherPacket.getPayload();
		if (ipPacket.getProtocol() != IPv4.PROTOCOL_UDP)
		{return; }

		UDP udpPacket = (UDP)ipPacket.getPayload();
		// Verify checksum
		short origCksum = udpPacket.getChecksum();
		udpPacket.resetChecksum();
		byte[] serialized = udpPacket.serialize();
		udpPacket.deserialize(serialized, 0, serialized.length);
		short calcCksum = udpPacket.getChecksum();
		if (origCksum != calcCksum)
		{ return; }

		// Verify RIP port 520
		if (udpPacket.getDestinationPort() != UDP.RIP_PORT)
		{return; }

		RIPv2 rip = (RIPv2)udpPacket.getPayload();
		if (rip.getCommand() == RIPv2.COMMAND_RESPONSE){
			System.out.println("Get a RIP Response Command");
			// send a solicited RIP response
			for (RIPv2Entry riPv2Entry: rip.getEntries()) {
				int cost = riPv2Entry.getMetric() + 1;
				riPv2Entry.setMetric(cost);

				RouteEntry found = this.routeTable.lookup(riPv2Entry.getAddress());
				if (found == null || found.getCost() > cost){
					if (found != null) {
						System.out.println("Find a better metric from: " + found.getCost() + "to: " + cost);
						this.routeTable.update(riPv2Entry.getAddress(), riPv2Entry.getNextHopAddress(),
								riPv2Entry.getSubnetMask(), inIface, cost);
					} else {
						System.out.println("Insert a new entry into route table");
						this.routeTable.insert(riPv2Entry.getAddress(), riPv2Entry.getNextHopAddress(),
								riPv2Entry.getSubnetMask(), inIface, cost);
					}

					for (Iface iface: this.interfaces.values()){
						// solicited RIP response
						this.sendRIP(iface, false, false);
					}
				}
			}
		} else if(rip.getCommand() == RIPv2.COMMAND_REQUEST){
			System.out.println("Get a RIP request Command");
			// send a unsolicited RIP response
			this.sendRIP(inIface, true, false);
			return;
		}

	}

	public void timeToResponse(){
		for (Iface iface: this.interfaces.values()){
			// unsolicited RIP response
			this.sendRIP(iface, true, false);
		}
	}

	class updateRIP extends TimerTask{
		public void run(){
			timeToResponse();
		}
	}
}
