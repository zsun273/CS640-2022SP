import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

public class Sender {
    private DatagramSocket senderSocket;
    private int senderPort;
    private String remoteIP;
    private int receiverPort;
    private String filename;
    private int mtu;
    private int sws;

    /** current sequence number*/
    private int sequenceNum;

    /** next expected sequence number after sending data*/
    private int nextSeqNum;

    /** current ack number*/
    private int currAck;

    /** Last Acked sequence position*/
    private int lastAcked;

    private Timer timer;

    private static final int SYN = 2;
    private static final int FIN = 1;
    private static final int ACK = 0;
    private static final int NUM_RETRANSMISSION = 16;
    private static final int TIME_OUT = 30000;

    private HashMap<Integer, byte[]> slidingWindow;
    private int payloadsize;
    boolean open;
    boolean stopSend;

    private int dataTransfered;
    private int numPacketsSent;
    private int getNumRetransmission;
    private int numDupAcks;

    public Sender(int senderPort, String remoteIP, int receiverPort, String filename, int mtu, int sws) {
        this.senderPort = senderPort;
        this.remoteIP = remoteIP;
        this.receiverPort = receiverPort;
        this.filename = filename;
        this.mtu = mtu;
        this.sws = sws;

        this.sequenceNum = 0;
        this.nextSeqNum = 0;
        this.currAck = 0;
        this.lastAcked = -1;
        this.slidingWindow = new HashMap<>(sws);
        this.open = false;
        this.stopSend = false;
        // mtu - header: (seq number + ack + timestamp + length + flag + all zero + checksum)
        this.payloadsize = mtu - 24;

        this.dataTransfered = 0;
        this.numPacketsSent = 0;
        this.getNumRetransmission = 0;
        this.numDupAcks = 0;

        System.out.println("Sender: from port: " + senderPort + " to port: " + receiverPort);

        try {
            this.senderSocket = new DatagramSocket(senderPort);
            System.out.println("Sender socket successfully bind to port: " + senderPort);
        } catch (SocketException e) {
            e.printStackTrace();
            System.out.println("Sender Socket binding failed!");
            System.exit(1);
        }

        try {
            ReceiveThread rthread = new ReceiveThread();
            rthread.start();
            SendThread sthread = new SendThread();
            sthread.start();
            System.out.println("Sender start listening with port " + senderPort);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(2);
        }

        try {
            // three-way handshake initialization
            // send first SYN here from sender
            // TODO: do we need to make sure only one thread is sending (using lock?)
            ArrayList<Integer> flagBits = new ArrayList<>();
            flagBits.add(SYN);
            byte[] data = createPacket(sequenceNum, new byte[0], flagBits);
            DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(remoteIP), receiverPort);
            senderSocket.send(packet);
            this.numPacketsSent ++;
            slidingWindow.put(sequenceNum, data);
            output(data, true);
            updateAfterSend(data);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(3);
        }
    }

    private void updateAfterSend(byte[] data) {
        // update next sequence number here
        int lengthNFlags = getLengthNFlags(data);
        int length = getLength(lengthNFlags);
        int s = getFlag(lengthNFlags, SYN);
        int f = getFlag(lengthNFlags, FIN);

        if (s == 1 || f == 1 || length > 0){
            sequenceNum = nextSeqNum;
            if (length > 0){
                nextSeqNum = sequenceNum + length;
            } else {
                nextSeqNum = sequenceNum + 1;
            }
        }
    }

    private void updateAfterReceive(byte[] data) {
        int lengthNFlags = getLengthNFlags(data);
        int length = getLength(lengthNFlags);
        int s = getFlag(lengthNFlags, SYN);
        int f = getFlag(lengthNFlags, FIN);
        int a = getFlag(lengthNFlags, ACK);

        int receivedSeqNum = getSequenceNum(data);
        if (s == 1 || f == 1 || length > 0){
            if (length > 0){
                currAck = receivedSeqNum + length;
            } else {
                currAck = receivedSeqNum + 1;
            }
        }

        if (a == 1){
            lastAcked = getAckNum(data) - 1; // what if out of order
            // refresh timer
            if (timer != null){
                timer.cancel();
            }
        }
        if (s == 1){
            open = true;
        }
        if (f == 1){
            open = false;
            stopSend = true;
        }
    }

    private void setTimer() {
        timer = new Timer();
        timer.schedule(new TimeOut(), TIME_OUT);
    }

    public class TimeOut extends TimerTask {

        public void run() {
            try {
                // TODO: maybe need lock here
                nextSeqNum = lastAcked + 1;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** Create a packet in required format */
    private byte[] createPacket(int sequenceNum, byte[] payload, ArrayList<Integer> flags) {
        byte[] sequenceNumBytes = ByteBuffer.allocate(4).putInt(sequenceNum).array();
        byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(currAck).array();
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(System.nanoTime()).array();

        int lengthAndFlags = payload.length << 3;
        for (int flag: flags){
            int flagBits = 1 << flag;               // SYN 2 -> 100, FYN 1 -> 10, ACK 0 -> 0
            lengthAndFlags = lengthAndFlags | flagBits;
        }

        byte[] lengthFlagsBytes = ByteBuffer.allocate(4).putInt(lengthAndFlags).array();
        byte[] zeros = new byte[2];
        short checkSum = computeCheckSum();             // TODO: do we need to calculate check sum here?
        byte[] checkSumBytes = ByteBuffer.allocate(2).putShort(checkSum).array();

        ByteBuffer packet = ByteBuffer.allocate(24 + payload.length);
        packet.put(sequenceNumBytes);
        packet.put(ackNumBytes);
        packet.put(timestampBytes);
        packet.put(lengthFlagsBytes);
        packet.put(zeros);
        packet.put(checkSumBytes);
        packet.put(payload);

        return packet.array();
    }

    public byte[] slicingByteArray(byte[] arr, int start, int end) {
        if (end >= arr.length) {
            end = arr.length;
        }
        // Get the slice of the Array
        byte[] slice = new byte[end - start];
        // Copy elements of arr to slice
        for (int i = 0; i < slice.length; i++) {
            slice[i] = arr[start + i];
        }
        return slice;
    }

    /** Length of data portion (in bytes) */
    public int getLength(int n) {
        return n >> 3;
    }

    public int getFlag(int n, int k){       // -->         ->          -
        return (n >> k) & 1;                // 100 - syn, 010 - ack, 001 - fin
    }

    public int getSequenceNum(byte[] packet){
        byte[] sequenceNumBytes = slicingByteArray(packet, 0, 4);
        return ByteBuffer.wrap(sequenceNumBytes).getInt();
    }

    public int getAckNum(byte[] packet){
        byte[] ackNumBytes = slicingByteArray(packet, 4, 8);
        return ByteBuffer.wrap(ackNumBytes).getInt();
    }

    public long getTimeStamp(byte[] packet){
        byte[] timeStampBytes = slicingByteArray(packet, 8, 16);
        return ByteBuffer.wrap(timeStampBytes).getLong();
    }

    public int getLengthNFlags(byte[] packet){
        byte[] lengthNFlagsBytes = slicingByteArray(packet, 16, 20);
        return ByteBuffer.wrap(lengthNFlagsBytes).getInt();
    }


    /** Format: [snd/rcv] [time] [flag-list] [seq-number] [number of bytes] [ack number] */
    private void output(byte[] packetBytes, boolean send) {
        String output = "";
        if (send == true){
            output += "snd";
        } else {
            output += "rcv";
        }
        output += " ";
        output += String.valueOf(System.nanoTime()); // TODO: current time or the timestamp in the packet
        output += " ";
        int lengthNFlags = getLengthNFlags(packetBytes);

        if (getFlag(lengthNFlags, SYN) == 1){
            output += "S ";
        } else {
            output += "- ";
        }
        if (getFlag(lengthNFlags, ACK) == 1){
            output += "A ";
        } else {
            output += "- ";
        }
        if (getFlag(lengthNFlags, FIN) == 1){
            output += "F ";
        } else {
            output += "- ";
        }
        if (getLength(lengthNFlags) > 0){
            output += "D ";
        } else {
            output += "- ";
        }

        output += String.valueOf(getSequenceNum(packetBytes)) + " " + String.valueOf(getLength(lengthNFlags)) + " "
                + String.valueOf(getAckNum(packetBytes));

        System.out.println(output);
    }

    private short computeCheckSum() {
        return (short)0;
    } // TODO: implement checksum computation

    /** Return timeout in nanoseconds */
    private long timeOutCalculation(int sequenceNum, long timeStamp) {
        long ERTT = 0, EDEV = 0, T0, SRTT = 0, SDEV;
        if (sequenceNum == 0){
            ERTT = System.nanoTime() - timeStamp;
            EDEV = 0;
            T0 = 2*ERTT;
        } else {
            SRTT = System.nanoTime() - timeStamp;
            SDEV = Math.abs(SRTT - ERTT);
            ERTT = (long) (0.875 * ERTT + (1-0.875) * SRTT);
            EDEV = (long) (0.75*EDEV + (1-0.75) * SDEV);
            T0 = ERTT + 4*EDEV;
        }
        return T0;
    }

    public class ReceiveThread extends Thread {
        public void run(){

        }
    }

    public class SendThread extends Thread {
        public void run() {

        }
    }
}
