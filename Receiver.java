import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;


public class Receiver {
	File file =new File("Receiver_log.txt");
	
	public static void main(String args[]) throws Exception {
		/******* arguments *****/
		int port = Integer.parseInt(args[0]); // receiver port
		File f = new File(args[1]);
		/***********************/
		DatagramSocket socket = new DatagramSocket(port);
		Receiver r = new Receiver();
		FileWriter fileWritter = new FileWriter(r.file.getName());
		BufferedWriter bufferWritter = new BufferedWriter(fileWritter); // write to the log file
		boolean established = false;
		int InitialSeqNum = 0;
		int NextSeqNum = InitialSeqNum;
		int SendBase = InitialSeqNum;
		int recSeqNum = 0;
		int recAckNum = 0;
		int SendAck = 0; // store the Receiver want to get the next value from Sender
		int MWS = 0;
		System.out.println("receiver run");
		long start = System.nanoTime();
		// establish the connection
		while (!established) {
			// listening
			if (SendBase == InitialSeqNum) {
				DatagramPacket request = new DatagramPacket(new byte[1024], 1024);
				socket.receive(request);
				recSeqNum = fetch_SeqNum(request);
				recAckNum = fetch_AckNum(request);
				MWS = fetch_windowSize(request);
				String log= "rcv	" + (double)(System.nanoTime()-start)/1000000.0 + "	S	" + recSeqNum + "	0	" + recAckNum;
    	        bufferWritter.write(log + '\n');
				System.out.println(log);
				
				// send reply
				InetAddress clientHost = request.getAddress();
				int clientPort = request.getPort();
				SendAck = recSeqNum + 1;
				Receiver.Segment segment = r.new Segment(SendBase, SendAck, true, true, false, null, MWS);
				DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, clientHost, clientPort);
				socket.send(sendPacket);
				log= "snd	" + (double)(System.nanoTime()-start)/1000000.0 + "	SA	" + SendBase + "	0	" + SendAck;
    	        bufferWritter.write(log + '\n');
				System.out.println(log);
				
				SendBase ++;
				
				//receive last ACK
				request = new DatagramPacket(new byte[1024], 1024);
				socket.receive(request);
				recSeqNum = fetch_SeqNum(request);
				recAckNum = fetch_AckNum(request);
				log= "rcv	" + (double)(System.nanoTime()-start)/1000000.0 + "	A	" + recSeqNum + "	0	" + recAckNum;
    	        bufferWritter.write(log + '\n');
				System.out.println(log);
			}
			established = true;
		}
		// keep listening , receive data and send Ack back;
		int windowSize = MWS;
		int offset = SendAck;
		byte[] DataBuffer = new byte[10000];
		boolean[] mark = new boolean[10000];
		boolean Finish = false;
		int nSegments = 0;
		int nDuplicates = 0;
		while (true) {
			if (windowSize == 0) { // (previous) a window's segments have transmitted successfully
				windowSize = MWS;
			}
   			DatagramPacket request = new DatagramPacket(new byte[1024], 1024);
   			socket.receive(request);
   			
   			recSeqNum = fetch_SeqNum(request);
			recAckNum = fetch_AckNum(request);
			if (request.getData()[9]%2 == 1) {  // if FIN flag is set
				Finish = true;
				String log= "rcv	" + (double)(System.nanoTime()-start)/1000000.0 + "	F	" + recSeqNum + "	0	" + recAckNum;
				bufferWritter.write(log + '\n');
				System.out.println(log);
				// set ACK flag and sent
				InetAddress clientHost = request.getAddress();
				int clientPort = request.getPort();
				Receiver.Segment segment = r.new Segment(recAckNum+1, recSeqNum+1, true, false, false, null, MWS);
				DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, clientHost, clientPort);
				socket.send(sendPacket);
				log= "snd	" + (double)(System.nanoTime()-start)/1000000.0 + "	A	" + (recAckNum+1) + "	0	" + (recSeqNum+1);
				bufferWritter.write(log + '\n');
   				System.out.println(log);
				// set Fin flag and sent
				segment = r.new Segment(recAckNum+1, recSeqNum+1, false, false, true, null, MWS);
				sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, clientHost, clientPort);
				socket.send(sendPacket);
				log= "snd	" + (double)(System.nanoTime()-start)/1000000.0 + "	F	" + (recAckNum+1) + "	0	" + (recSeqNum+1);
				bufferWritter.write(log + '\n');
				System.out.println(log);
   				// receive the last Ack
   				socket.receive(request);
   				recSeqNum = fetch_SeqNum(request);
   				recAckNum = fetch_AckNum(request);
   				log= "rcv	" + (double)(System.nanoTime()-start)/1000000.0 + "	A	" + recSeqNum + "	0	" + recAckNum;
   				bufferWritter.write(log + '\n');
   				System.out.println(log);
				break;
			}
			
			System.arraycopy(request.getData(), fetch_headerLength(request), DataBuffer, (recSeqNum - offset), request.getLength() - fetch_headerLength(request));
//			System.out.write(DataBuffer);
			markList(mark, request.getLength() - fetch_headerLength(request), recSeqNum - offset); // used to faciliate the next Send Ack's position
			//log
			String log= "rcv	" + (double)(System.nanoTime()-start)/1000000.0 + "	D	" + recSeqNum + "	" + (request.getLength() - fetch_headerLength(request))	+ "	" + recAckNum;
			bufferWritter.write(log + '\n');
			System.out.println(log);
   			
   			// send reply
   			InetAddress clientHost = request.getAddress();
   			int clientPort = request.getPort();
   			
   			if (recSeqNum == SendAck) {
   				windowSize = windowSize - (request.getLength() - fetch_headerLength(request));
//   				SendAck = SendAck + request.getLength() - fetch_headerLength(request);
   				SendAck = updateSendAck(SendAck, mark, offset);
   				Receiver.Segment segment = r.new Segment(recAckNum, SendAck, false, true, false, null, windowSize);
   				DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, clientHost, clientPort);
   				socket.send(sendPacket);
   				log= "snd	" + (double)(System.nanoTime()-start)/1000000.0 + "	A	" + recAckNum + "	0	" + SendAck;
   				bufferWritter.write(log + '\n');
   				System.out.println(log);
//   				System.out.println("windowSize: " + windowSize);
   			} else {
   				Receiver.Segment segment = r.new Segment(SendBase, SendAck, false, true, false, null, windowSize);
   				DatagramPacket sendPacket = new DatagramPacket(segment.createSegment(), segment.segment_length, clientHost, clientPort);
   				socket.send(sendPacket);
   				log= "snd	" + (double)(System.nanoTime()-start)/1000000.0 + "	A	" + SendBase + "	0	" + SendAck;
   				bufferWritter.write(log + '\n');
   				System.out.println(log);
   				nDuplicates ++;
   			}
   			nSegments ++ ;
//   			System.out.println("I want to know: " + SendAck);
   			
   		} 		
		
		if (Finish) {
			int count = 0;
			for (int i = 0; i < DataBuffer.length; ++i) {
				if (DataBuffer[i] == 0) {
					break;
				}
				count++;
			}  
			
			byte[] buffer = new byte[count];
			System.arraycopy(DataBuffer, 0, buffer, 0, count);
			FileOutputStream fos = new FileOutputStream(args[1]);
			fos.write(buffer);
			fos.close();
			socket.close();
			bufferWritter.write("Amount of Data Received (in bytes): " + count + '\n');
			bufferWritter.write("Number of Data Segments Received: " + nSegments + '\n' );
			bufferWritter.write("Number of duplicate Segments received: " + nDuplicates + '\n');
			bufferWritter.close();
		}
		
	}
	
	/*
	 *  header structure: 
	 *  4 bytes sequence number
	 *  4 bytes acknowledge bytes
	 *  2 bytes flag, 2 bytes window
	 *  flag last 6 bits: [URG, ACK, PSH, RST,SYN, FIN]
	 */
	public class Segment {
		// constructor
		Segment (int SeqNum, int AckNum, boolean ACK, boolean SYN, boolean FIN, byte[] d, int WindowSize) {
			this.SeqNum_ = SeqNum;
			this.AckNum_ = AckNum;
			this.ACK_ = ACK;
			this.SYN_ = SYN;
			this.FIN_ = FIN;
			this.data = d;
			if (data != null) {this.data_length = d.length;}
			this.segment_length = this.data_length + this.headerSize_;
			this.window_size = WindowSize;
		}
		public int get_SeqNum (){
			return SeqNum_;
		}
		public int get_AckNum (){
			return AckNum_;
		}
		public boolean get_AckFlag () {
			return ACK_;
		}
		public boolean get_SynFlag () {
			return SYN_;
		}
		public boolean get_FinFlag () {
			return FIN_;
		}
		public int get_windowSize() {
			return window_size;
		}
		
		// produce a byte array type header
		public byte[] createHeader () {
			byte[] h = new byte[headerSize_];
			h[9] = 0; // initiate the flag byte
			// add SeqNum to the header segment
			h[0] = (byte)((SeqNum_ >> 24) & 0xFF);
			h[1] = (byte)((SeqNum_ >> 16) & 0xFF);
			h[2] = (byte)((SeqNum_ >> 8) & 0xFF);
			h[3] = (byte)(SeqNum_ & 0xFF);
			// add AckNum to the header segment
			h[4] = (byte)((AckNum_ >> 24) & 0xFF);
			h[5] = (byte)((AckNum_ >> 16) & 0xFF);
			h[6] = (byte)((AckNum_ >> 8) & 0xFF);
			h[7] = (byte)(AckNum_ & 0xFF);
			// set Ack flag in the header segment
			if (ACK_ == true) {
				h[9] += 1<<4;
			}
			if (SYN_ == true) {
				h[9] += 1<<1;
			}
			if (FIN_ == true) {
				h[9] += 1;
			}
			h[10] = (byte)((window_size >> 8) & 0xFF);
			h[11] = (byte)(window_size & 0xFF);
			return h;
		}
		// produce a STP segment
		public byte[] createSegment () {
			byte[] h = createHeader();
			if (data_length != 0) {
				byte[] segment = new byte[data_length + headerSize_];
				System.arraycopy(h, 0, segment, 0, headerSize_);
				System.arraycopy(data, 0, segment, headerSize_, data_length);
				return segment;
			} else {
				return h;
			}
		}
		
		private	int SeqNum_;
		private	int AckNum_;
		private	boolean ACK_ = false;
		private	boolean SYN_ = false;
		private	boolean FIN_ = false; 
		private int headerSize_ = 12;
		private byte[] data;
		private int data_length = 0;
		private int segment_length = 0;
		private int window_size = 0;
	}
	
	// fetch SeqNum from receive package
	public static int fetch_SeqNum (DatagramPacket P) {
		int SeqNum = 0;
		SeqNum += (P.getData()[3] & 0xff);
		SeqNum += (P.getData()[2] & 0xff) << 8;
		SeqNum += (P.getData()[1] & 0xff) << 16;
		SeqNum += (P.getData()[0] & 0xff) << 24;
		return SeqNum;
	}
	
	// fetch AckNum from receive pack
	public static int fetch_AckNum (DatagramPacket P) {
		int AckNum = 0;
		AckNum += (P.getData()[7] & 0xff);
		AckNum += (P.getData()[6] & 0xff) << 8;
		AckNum += (P.getData()[5] & 0xff) << 16;
		AckNum += (P.getData()[4] & 0xff) << 24;
		return AckNum;
	}
	
	// fetch header length
	public static int fetch_headerLength (DatagramPacket P) {
		return (int)P.getData()[8];
	}
	
	// fetch window size
	public static int fetch_windowSize (DatagramPacket P) {
		int windowSize = 0;
		windowSize += P.getData()[11] & 0xff;
		windowSize += (P.getData()[10] & 0xff) << 8;
		return windowSize;
	}
		
	// fetch data
	public static byte[] fetch_data (DatagramPacket P) {
		byte[] data ;
		data = new byte[P.getLength() - fetch_headerLength(P)];
		System.arraycopy(P.getData(), fetch_headerLength(P), data, 0, P.getLength()-fetch_headerLength(P));
		return data;		
	}
	// mark
	public static void markList (boolean[] mark, int l, int start) {
		for (int i = start; i < start+l; ++i) {
			mark[i] = true;
		}
	}
	
	// check and update SendAck, jump to next Ack Number
	public static int updateSendAck (int SendAck, boolean[] mark, int offset) {
		int r = SendAck;
		for (int i = SendAck - offset; i < mark.length; i++) {
			if (mark[i] == false) {
				r = i+offset;
				break;
			}
		}
		return r;
	}
	
	// print Data
	static void printData (byte[] data) throws Exception {
		ByteArrayInputStream bais = new ByteArrayInputStream(data);
		InputStreamReader isr = new InputStreamReader(bais);
		BufferedReader br = new BufferedReader(isr);
		String line = br.readLine();
		System.out.println (new String(line));
	}
}
