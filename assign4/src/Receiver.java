import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Arrays;

/**
 * Receiver Class
 * When receiving packets:
 * 1. if receive a SYN from sender, send back a SYN+ACK, connection established
 * 2. if receive data from sender, check if checksum is correct,
 *                                 check if the data packet is out of sequence, if out-of-sequence, put in the buffer
 *                                 if everything correct, write the data to the designated path and send back ack
 * 3. if receive a FIN from sender, make sure all received data have been written, send back a FIN+ACK, close connection
 */
public class Receiver {
    private int receiverPort;
    private int mtu;           // how is mtu used in receiver
    private int sws;
    private String filename;

    private HashMap<Integer, byte[]> buffer;
    private Timer timer;
    private DatagramSocket listenSocket;

    /** Ack number of receiver, next byte receiver expects*/
    private int receiverACK;

    private int lastAcked;
    private int sequenceNum;
    private int nextSeqNum;

    private static final int SYN = 2;
    private static final int FIN = 1;
    private static final int ACK = 0;

    boolean open;
    boolean stopReceive;
    boolean update;
    private long startTime;
    private FileOutputStream fileWriter;
    private int dataWritten;

    private int dataReceived;
    private int numPacketsReceived;
    private int numOutOfSeq;
    private int wrongCheckSum;

    public Receiver(int receiverPort, int mtu, int sws, String filename) {
        this.receiverPort = receiverPort;
        this.mtu = mtu;
        this.sws = sws;
        this.filename = filename;
        this.timer = new Timer();

        System.out.println("Receiver: receiver port = " + receiverPort);

        this.sequenceNum = 0;
	    this.nextSeqNum = 0;
        this.receiverACK = 0;
        this.open = false;
        this.stopReceive = false;
        this.update = false;
        this.buffer = new HashMap<>();

        this.startTime = System.nanoTime();
        try{
            this.fileWriter = new FileOutputStream(new File(filename), true);
        } catch (FileNotFoundException e) {
            System.out.println("file or path not found");
            e.printStackTrace();
        }
        this.dataWritten = 0;

        this.dataReceived = 0;
        this.numPacketsReceived = 0;
        this.numOutOfSeq = 0;
        this.wrongCheckSum = 0;

        // bind socket to the port
        try {
            this.listenSocket = new DatagramSocket(receiverPort);
            System.out.println("Receiver socket successfully bind to port: " + receiverPort);
            try {
                //byte[] incomingData = new byte[mtu];
                //DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);

                while(stopReceive == false){
                    byte[] incomingData = new byte[24 + mtu];
		            DatagramPacket incomingPacket = new DatagramPacket(incomingData, incomingData.length);
		    // Receiving
                    // System.out.println("Receiver: receiving data.....");
                    listenSocket.receive(incomingPacket);

                    int lengthNFlags = getLengthNFlags(incomingData);
                    int length = getLength(lengthNFlags);
                    int s = getFlag(lengthNFlags, SYN);
                    int f = getFlag(lengthNFlags, FIN);
                    int a = getFlag(lengthNFlags, ACK);

                    //incomingData = slicingByteArray(incomingData, 0, incomingPacket.getLength());
                    //for(int i=0; i< incomingData.length ; i++) {
                    //    System.out.print(incomingData[i] +" ");
                    //}
		            //System.out.println();

                    short originalChecksum = getCheckSum(incomingData);
                    // reset checksum to zero
                    ByteBuffer bb = ByteBuffer.wrap(incomingData);
                    bb.putShort(22, (short)0);
                    // compute current checksum
                    short currChecksum = computeCheckSum(incomingData);
                    if (currChecksum != originalChecksum) {
                        System.out.println("Check sum failed! Data Corrupted!");
                        // drop the packet
                        wrongCheckSum ++ ;
                        continue;
                    }

                    // check if out-of-sequence
                    if (s == 1 || f == 1 || length > 0){
                        if (receiverACK < getSequenceNum(incomingData)) {
                            // incoming packet's sequence number not what receiver expected
                            // System.out.println("Receive out of sequence.");
                            numOutOfSeq ++;
                            // put out-of-sequence packet into buffer
                            if (buffer.size() < sws){
                                buffer.put(getSequenceNum(incomingData), incomingData);
                                continue;
                            }
                            else{
                                // cannot put in buffer, drop and continue
                                System.out.println("Buffer full.");
                                continue;
                            }

                        }
                        
                    }

                    // check complete, handle incoming packet and send back corresponding ack
                    int senderPort = incomingPacket.getPort();
                    InetAddress senderAddr = incomingPacket.getAddress();

                    // print out the received packet
                    output(incomingData, false);

                    //System.out.println("Expected Seq: " + receiverACK + " Actual Seq: "+ getSequenceNum(incomingData));
                    // update variables after receiving the packet
                    if (receiverACK == getSequenceNum(incomingData)){
                        updateAfterReceive(incomingData);
                        // update = true;
                    }

                    // check if there is data in the buffer can be written
                    try{
                        while(buffer.containsKey(receiverACK)) { // buffer contains next sequence number
                            incomingData = buffer.get(receiverACK); // change incoming data to the data we will write
                            lengthNFlags = getLengthNFlags(incomingData);
                            length = getLength(lengthNFlags);

                            byte[] payload = getPayload(buffer.get(receiverACK));
                            fileWriter.write(payload, 0, length);
                            buffer.remove(receiverACK);
                            receiverACK += length;
                            System.out.println(length + " bytes of data written from buffer");
                            System.out.println("Now Expected Sequence number change to: " + receiverACK); 
                        
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    //System.out.println("open status: " + open + " length: " + length);
                    // send a packet back to Sender
                    // if we receive a syn -> send back syn and ack
                    // if we receive fin -> send back ack and fin
                    ArrayList<Integer> flagBits = new ArrayList<>();
                    if (s == 1 || f == 1) {
                        if (f == 1) {
                            flagBits.add(FIN);
                            nextSeqNum = 1;
			                //this.stopReceive = true;
                            fileWriter.close();
                        }
                        if (s == 1) {
                            flagBits.add(SYN);
                            nextSeqNum = 0;
                        }
                        flagBits.add(ACK);
                        byte[] returnPacket = createPacket(nextSeqNum, new byte[0], flagBits, getTimeStamp(incomingData));
                        listenSocket.send(new DatagramPacket(returnPacket, returnPacket.length, senderAddr, senderPort));

                        if (f == 1)
                            setTimeout(returnPacket, senderAddr, senderPort);

                        // print out the sent packet
                        output(returnPacket, true);
                           
                        // update variables after sending the packet
                        // if (update){              
                        //    updateAfterSend(returnPacket);
                        // }
                    }
                    else if (length > 0) {// if we receive data -> send back ack
                        if (getAckNum(incomingData) == 1) {
                            open = true;
                        }
                        if (open == true){
                            flagBits.add(ACK);
                            byte[] returnPacket = createPacket(1, new byte[0], flagBits, getTimeStamp(incomingData));
                            listenSocket.send(new DatagramPacket(returnPacket, returnPacket.length, senderAddr, senderPort));

                            // print out the sent packet
                            output(returnPacket, true);

                            // update variables after sending the packet
                            //if (update){
                            //    updateAfterSend(returnPacket);
                            //}
                        }
                    }
                    else {
                        // only receive an ack back
                        if (getAckNum(incomingData) == 1) {
                            open = true;
                        }
                        continue;
                    }

                }
                System.out.println("Amount of data received: " + dataReceived);
                System.out.println("Number of packets received: " + numPacketsReceived);
                System.out.println("Number of out-of-sequence: " + numOutOfSeq);
                System.out.println("Number of packets discarded due to checksum: " + wrongCheckSum);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } catch (SocketException e) {
            e.printStackTrace();
            System.out.println("Sender Socket binding failed!");
            System.exit(1);
        }


    }

    private void setTimeout(byte[] returnPacket, InetAddress senderAddr, int senderPort) { // resend FIN/ACK every 500ms until ack received
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                try {
                    listenSocket.send(new DatagramPacket(returnPacket, returnPacket.length, senderAddr, senderPort));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        };
        timer.schedule(task, 500 ,500);
    }


    private void updateAfterReceive(byte[] data) {
        int lengthNFlags = getLengthNFlags(data);
        int length = getLength(lengthNFlags);
        int s = getFlag(lengthNFlags, SYN);
        int f = getFlag(lengthNFlags, FIN);
        int a = getFlag(lengthNFlags, ACK);

        int receivedSeqNum = getSequenceNum(data);
        numPacketsReceived ++ ;
        if (s == 1 || f == 1 || length > 0){
            if (length > 0){
                receiverACK = receivedSeqNum + length;
                dataReceived += length;

                try {
                    byte[] payload = getPayload(data);
                    //System.out.println("Datawritten: " + dataWritten + " Payload length:" + payload.length + "Length: " + length);
                    fileWriter.write(payload, 0, length);
                    dataWritten += length;
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                receiverACK = receivedSeqNum + 1;
            }
        } else if (a == 1) {
            if (getAckNum(data) == 2) {
                timer.cancel();
                stopReceive = true;
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
            //System.out.println("nextSeqnum updated to: " + nextSeqNum);
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
        short checkSum = 0;
        byte[] checkSumBytes = ByteBuffer.allocate(2).putShort(checkSum).array();

        ByteBuffer packet = ByteBuffer.allocate(24 + payload.length);
        packet.put(sequenceNumBytes);
        packet.put(ackNumBytes);
        packet.put(timestampBytes);
        packet.put(lengthFlagsBytes);
        packet.put(zeros);
        packet.put(checkSumBytes);
        packet.put(payload);

        if (checkSum == 0) {
            checkSum = computeCheckSum(packet.array());
            packet.putShort(22, checkSum);
        }

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

    public short getCheckSum(byte[] packet){
        byte[] checksumBytes = slicingByteArray(packet, 22, 24);
        return ByteBuffer.wrap(checksumBytes).getShort();
    }

    public byte[] getPayload(byte[] packet) {
        return slicingByteArray(packet, 24, packet.length);
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
        String time = String.valueOf((double)(System.nanoTime() - this.startTime) / Math.pow(10, 9));
        output += time.substring(0, 6);
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

    // not sure if correct
    private short computeCheckSum(byte[] packet) {
        ByteBuffer bb = ByteBuffer.wrap(packet);
        bb.rewind();
        int accumulation = 0;
        for (int i = 0; i < packet.length/2; i++){
            accumulation += 0xffff & bb.getShort();
        }
        // pad to an even number of shorts
        if (packet.length % 2 > 0){
            accumulation += (bb.get() & 0xff) << 8;
        }
        // add potential carry over
        accumulation = ((accumulation >> 16) & 0xffff) + (accumulation & 0xffff);
        short checksum = (short) (~accumulation & 0xffff);
        return checksum;
    }
}
