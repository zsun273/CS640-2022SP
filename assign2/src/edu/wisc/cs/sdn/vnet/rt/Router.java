package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import java.util.Map;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device
{
    /** Routing table for the router */
    private RouteTable routeTable;

    /** ARP cache for the router */
    private ArpCache arpCache;

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

        // first, check is the packet contains IPv4 packet
        if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) {
            System.out.println("Dropped because IPv4 type failed\n");
            return;
        }

        // verify the checksum of the IPv4 packet
        IPv4 ipPacket = (IPv4) etherPacket.getPayload();
        //int headerLength = ipPacket.getHeaderLength().intValue() * 4;
        short currChecksum = ipPacket.getChecksum();
        ipPacket.setChecksum((short)0);									// zeroed checksum before calculating
        byte[] serializedData = ipPacket.serialize();
        ipPacket.deserialize(serializedData, 0, serializedData.length);
        if (currChecksum != ipPacket.getChecksum()) {
            System.out.println("Dropped because checksum failed\n");
            return;
        }

        // decrement and verify the TTL of the packet
        ipPacket.setTtl((byte)(ipPacket.getTtl() - 1));
        if (ipPacket.getTtl() <= 0){
            System.out.println("Dropped because TTL failed\n");
            return;
        }
        ipPacket.resetChecksum();

        Map<String,Iface> interfaces = this.interfaces;
        for (Map.Entry<String, Iface> entry : this.interfaces.entrySet()){
            if (ipPacket.getDestinationAddress() == entry.getValue().getIpAddress()) {
                System.out.println("Dropped because destination is in one of router's interface\n");
                return;
            }
        }

        // forward the packet
        RouteEntry longestMatch = this.routeTable.lookup(ipPacket.getDestinationAddress());
        if (longestMatch == null){
            System.out.println("Dropped because no match in route table\n");
            return;
        }
        int nextHop = longestMatch.getGatewayAddress();
        if (nextHop == 0){  // TODO: whether we need to change 0 to destination ip
            nextHop = ipPacket.getDestinationAddress();
        }
        ArpEntry arpEntry = this.arpCache.lookup(nextHop);
        if (arpEntry == null){
            System.out.println("Dropped because ARP cache cannot found this next hop\n");
            return;
        }
        etherPacket.setDestinationMACAddress(arpEntry.getMac().toBytes());

        Iface outIface = longestMatch.getInterface();
        etherPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());

        this.sendPacket(etherPacket, outIface);
        /********************************************************************/
    }
}
