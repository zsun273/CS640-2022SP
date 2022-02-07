/**
 * CS640 2022 SP
 * ASSIGNMENT 1: IPERFER IMPLEMENTATION
 * @AUTHOR: ZHUOCHENG SUN, YINGZHU XIONG
 * DATE: FEB 4, 2022
 */

import java.io.*;
import java.net.*;

public class Iperfer {

    public static void main(String[] args){

        // declare parameters needed for the command line
        String mode = "", hostname = null;
        int serverPortNum = 0, listenPort = 0, time = 0;
        Boolean portFlag = false, timeFlag = false, hostnameFlag = false;

        // parse arguments from command line
        if(args.length == 7){
            for (String arg: args) {
                if (arg.equals("-c")){
                    mode = "client";
                }
                else if(arg.equals("-h")){
                    hostnameFlag = true;
                }
                else if(arg.equals("-p")){
                    portFlag = true;
                }
                else if(arg.equals("-t")){
                    timeFlag = true;
                }
            }
            if (!(mode.equals("client") && hostnameFlag && portFlag && timeFlag)){
                System.out.println("Error: missing or additional arguments");
                System.exit(1);
            }
            hostname = args[2];
            try{
                serverPortNum = Integer.parseInt(args[4]);
                time = Integer.parseInt(args[6]);
            } catch (NumberFormatException e){
                System.out.println("Error: missing or additional arguments");
                System.exit(1);
            }
            // check if port number is in valid range
            if (serverPortNum < 1024 || serverPortNum > 65535){
                System.out.println("Error: port number must be in range 1024 to 65535");
                System.exit(2);
            }
        }
        else if(args.length == 3){
            for (String arg: args) {
                if (arg.equals("-s")){
                    mode = "server";
                }
                else if(arg.equals("-p")){
                    portFlag = true;
                }
            }
            if (!(mode.equals("server") && portFlag)){
                System.out.println("Error: missing or additional arguments");
                System.exit(1);
            }
            try{
                listenPort = Integer.parseInt(args[2]);
            } catch(NumberFormatException e){
                System.out.println("Error: missing or additional arguments");
                System.exit(1);
            }
        }
        else{
            System.out.println("Error: missing or additional arguments");
            System.exit(1);
        }

        // run specified mode using specified parameters
        if (mode.equals("client")){
            System.out.println("Run client here");
            clientService(hostname, serverPortNum, time);

        }
        else if(mode.equals("server")){
            System.out.println("Run server here");
            serverService(listenPort);
        }
        else{
            System.out.println("Error: missing or additional arguments");
            System.exit(1);
        }
        System.exit(0);
    }

    private static void clientService(String hostname, int portNumber, int time){
        double kilobytesSend = 0;
        double rate = 0;
        byte[] packet = new byte[1000];
        long endTime = System.currentTimeMillis() + 1000*time;
        try{
            Socket clientSocket = new Socket(hostname, portNumber);
            while(System.currentTimeMillis() < endTime){
                clientSocket.getOutputStream().write(packet);
                kilobytesSend += 1000;
            }
            clientSocket.close();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        rate = (8*kilobytesSend) / (10e6 * time);
        System.out.println("sent=" + kilobytesSend/1000 + " KB rate=" + rate + " Mbps");
    }

    private static void serverService(int portNumber){
        double received = 0, bytesRead = 0;
        double startTime = 0, endTime = 0;
        double rate, time;
        byte[] packet = new byte[1000];
        try{
            ServerSocket serverSocket = new ServerSocket(portNumber);
            Socket clientSocket = serverSocket.accept();
            startTime = System.currentTimeMillis();
            while(bytesRead != -1){
                bytesRead = clientSocket.getInputStream().read(packet, 0, 1000);
                received += bytesRead;
            }
            endTime = System.currentTimeMillis();
            if (bytesRead == -1){
                received += 1;
            }
            clientSocket.close();
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        time = (endTime-startTime)/1000; // convert time into seconds
        rate = (8*received)/(10e6 * time);
        System.out.println("received=" + received/1000 + " KB rate=" + rate + " Mbps");
    }
}
