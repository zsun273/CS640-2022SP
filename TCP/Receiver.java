import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

public class Receiver {
    private int receiverPort;
    private int mtu;
    private int sws;
    private String filename;

    private HashMap<Integer, byte[]> buffer;
    private Timer timer;
    private DatagramSocket listenSocket;

    /** Ack number of receiver, next byte receiver expects*/
    private int receiverACK;

    /** Ack number from sender, next byte sender expects*/
    private int senderACK;

    private int lastAcked;
    private int sequenceNum;
    private int nextSeqNum;
    private int payloadsize;

    private static final int SYN = 2;
    private static final int FIN = 1;
    private static final int ACK = 0;
    private static final int TIME_OUT = 30000;

    boolean open;
    boolean stopReceive;
    private long startTime;

    private int dataReceived;
    private int numPacketsReceived;
    private int numOutOfSeq;
    private int wrongCheckSum;

    public Receiver(int receiverPort, int mtu, int sws, String filename) {
        this.receiverPort = receiverPort;
        this.mtu = mtu;
        this.sws = sws;
        this.filename = filename;

        System.out.println("Receiver: receiver port = " + receiverPort);

        this.sequenceNum = 0;
        this.receiverACK = 0;
        this.senderACK = 0;
        this.open = false;
        this.stopReceive = false;
        this.buffer = new HashMap<>(sws);


        this.dataReceived = 0;
        this.numPacketsReceived = 0;
        this.numOutOfSeq = 0;
        this.wrongCheckSum = 0;

        // bind socket to the port
        try {
            this.listenSocket = new DatagramSocket(receiverPort);
            System.out.println("Receiver socket successfully bind to port: " + receiverPort);
            try {
                byte[] incomingData = new byte[mtu];
                DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);

                while(stopReceive == false){
                    // Receiving
                    listenSocket.receive(incomingPacket);

                    int lengthNFlags = getLengthNFlags(incomingData);
                    int length = getLength(lengthNFlags);
                    int s = getFlag(lengthNFlags, SYN);
                    int f = getFlag(lengthNFlags, FIN);
                    int a = getFlag(lengthNFlags, ACK);

                    // TODO: Check if the checksum is correct

                    // check if out-of-sequence
                    if (s == 1 || f == 1 || length > 0){
                        if (receiverACK != getSequenceNum(incomingData)) {
                            // incoming packet's sequence number not what receiver expected
                            numOutOfSeq ++;
                            // put out-of-sequence packet into buffer
                            buffer.put(getSequenceNum(incomingData), incomingData);
                            continue;
                        }
                    }

                    // TODO: check if ACK number
                    if (a == 1) { // this packet contains ack number
                        if (nextSeqNum != getAckNum(incomingData)){
                            // the ACK number sender sends back is not what we expected
                            // something wrong with previous packet

                            // resend previous packet?
                            continue;
                        }
                    }

                    // check complete, handle incoming packet and send back corresponding ack
                    int senderPort = incomingPacket.getPort();
                    InetAddress senderAddr = incomingPacket.getAddress();

                    // print out the received packet
                    output(incomingData, false);

                    // update variables after receiving the packet
                    updateAfterReceive(incomingData);

                    // send a packet back to Sender
                    // if we receive a syn -> send back syn and ack
                    // if we receive fin -> send back ack and fin
                    ArrayList<Integer> flagBits = new ArrayList<>();
                    if (s == 1 || f == 1) {
                        if (f == 1) {
                            flagBits.add(FIN);
                        }
                        if (s == 1) {
                            flagBits.add(SYN);
                        }
                        flagBits.add(ACK);
                        byte[] returnPacket = createPacket(sequenceNum, new byte[0], flagBits, getTimeStamp(incomingData));
                        listenSocket.send(new DatagramPacket(returnPacket, returnPacket.length, senderAddr, senderPort));

                        // print out the sent packet
                        output(returnPacket, true);

                        // update variables after sending the packet
                        updateAfterSend(returnPacket);
                    }
                    else if (length > 0) {// if we receive data -> send back ack
                        if (open == true){
                            flagBits.add(ACK);
                            byte[] returnPacket = createPacket(sequenceNum, new byte[0], flagBits, getTimeStamp(incomingData));
                            listenSocket.send(new DatagramPacket(returnPacket, returnPacket.length, senderAddr, senderPort));

                            // print out the sent packet
                            output(returnPacket, true);

                            // update variables after sending the packet
                            updateAfterSend(returnPacket);
                        }
                    }
                    else {
                        // not sure
                        continue;
                    }

                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (SocketException e) {
            e.printStackTrace();
            System.out.println("Sender Socket binding failed!");
            System.exit(1);
        }


    }

//    private int checkACK(byte[] data) {
//        int lengthNFlags = getLengthNFlags(data);
//        int length = getLength(lengthNFlags);
//        int s = getFlag(lengthNFlags, SYN);
//        int f = getFlag(lengthNFlags, FIN);
//        int a = getFlag(lengthNFlags, ACK);
//
//        if (a == 1) { // this packet contains ack number
//            if (nextSeqNum != getAckNum(data)){
//                return -1;
//            }
//        }
//
//        if (s == 1 || f == 1 || length > 0){
//            if (receiverACK != getSequenceNum(data)) {
//                return -1;          // incoming packet's sequence number not what receiver expected
//            }
//        }
//
//        return getAckNum(data);
//    }

    private void updateAfterReceive(byte[] data) {
        int lengthNFlags = getLengthNFlags(data);
        int length = getLength(lengthNFlags);
        int s = getFlag(lengthNFlags, SYN);
        int f = getFlag(lengthNFlags, FIN);
        int a = getFlag(lengthNFlags, ACK);

        int receivedSeqNum = getSequenceNum(data);
        if (s == 1 || f == 1 || length > 0){
            if (length > 0){
                receiverACK = receivedSeqNum + length;
                dataReceived += length;
                // TODO: write the data to the file
            } else {
                receiverACK = receivedSeqNum + 1;
            }
        }

        if (a == 1){
            lastAcked = getAckNum(data) - 1; // what if out of order
            // refresh timer
            if (timer != null){
                timer.cancel();
            }
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
    private byte[] createPacket(int sequenceNum, byte[] payload, ArrayList<Integer> flags, long timestamp) {
        byte[] sequenceNumBytes = ByteBuffer.allocate(4).putInt(sequenceNum).array();
        byte[] ackNumBytes = ByteBuffer.allocate(4).putInt(receiverACK).array();
        byte[] timestampBytes = ByteBuffer.allocate(8).putLong(timestamp).array();

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
}
