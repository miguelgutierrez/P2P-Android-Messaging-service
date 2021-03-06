package com.p2pwifidirect.connectionmanager;


import java.io.FileInputStream;
import java.io.FileOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Random;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.WifiP2pManager.*;
import android.os.AsyncTask;
import android.os.Looper;

import android.text.Layout;
import android.view.View;
import android.widget.TextView;


public class P2PConnectionManager extends BroadcastReceiver
	implements  WifiP2pManager.ActionListener, WifiP2pManager.ChannelListener,
				 WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener, 
				  WifiP2pManager.PeerListListener {
		
	Boolean discoveryOn = false;
	int childportstart = 5560;
	String myMAC = "";
	
    Date date;
    SimpleDateFormat dateFormat;
    Random rnd;

	Context cntxt;
	Looper lpr;
	AlarmManager alrmmgr;
	AlertDialog.Builder adbldr;
    TextView console;
	IntentFilter intntfltr;

	Channel chnl;
	WifiP2pManager p2pmgr;
    WifiP2pDeviceList curdlist;
	P2PConnectionAdapter adapter;
	ArrayList<P2PConnection> connections;

	
	public P2PConnectionManager(Context c, Looper l, WifiP2pManager mgr, TextView con){
		
		cntxt = c;
		lpr = l;
		p2pmgr = mgr;
		adbldr = new AlertDialog.Builder(cntxt);
		console = con;
		connections = new ArrayList<P2PConnection>();
    	adapter = new P2PConnectionAdapter(cntxt,connections);
    	rnd = new Random();
    	dateFormat = new SimpleDateFormat("HH:mm:ss");
    	date = new Date();
    	
    	//this grabs the MAC and writes it to a file but requires regular wifi to be on
    	//a special version of the app needs to be run on the phone first with this code
    	//commented in, otherwise we can't get the local wifi's MAC address
    	/*WifiManager wifiMan = (WifiManager) cntxt.getSystemService(
                Context.WIFI_SERVICE);
    	WifiInfo wifiInf = wifiMan.getConnectionInfo();
    	myMAC = wifiInf.getMacAddress();
    	if(myMAC != null){
		try{
			createFileWithString("myMACaddress.txt",myMAC);
		}catch(IOException e){
			System.out.println(e.toString());
		}
		}*/

    	//this grabs the MAC address of the wifi interface from the file generated by the code above
    	myMAC = getMyMacAddress();
    	appendToConsole("CMGR: My MAC (from file) is - " + myMAC);
    	
    	//this sets up the scan alarm which repeats every 5 seconds when scanning is on
    	alrmmgr = (AlarmManager)cntxt.getSystemService(Context.ALARM_SERVICE);
    	Intent i=new Intent("scanAlarm");
    	PendingIntent pi=PendingIntent.getBroadcast(cntxt, 0, i, 0);
        alrmmgr.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), 1000 * 5, pi); // Millisec * Second * Minute

        //this is our intenent filter where we register to receive android wifi p2p intents
        //connection intents and the scan alarm
		intntfltr = new IntentFilter();
		intntfltr.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
		intntfltr.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
		intntfltr.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
		intntfltr.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
		intntfltr.addAction("scanAlarm");
		intntfltr.addAction("RestartServer");
		cntxt.registerReceiver(this, intntfltr);
		
		chnl = p2pmgr.initialize(cntxt, lpr, null);

	}
	
	//once this is called, android throws a PEERS_CHANGED_EVENT if successful
	public void discoverPeers(){	
		p2pmgr.discoverPeers(chnl, this);
	}
	
	//allow manager to discover peers and connect to other devices
	public void startDiscovery(){ 
		discoveryOn = true;
		Intent i = new Intent("scanAlarm");
		cntxt.sendBroadcast(i);
	}
	
	//stop trying to connect to other devices
	public void stopDiscovery(){ discoveryOn = false; }
			
	//removes all connections associated with the channel
	//then calls the disconnect method of each connection
	//disconnect in P2PConnection should maybe send a closedown message?
	public void closeConnections(){ 
		p2pmgr.removeGroup(chnl, this); 
		Iterator<P2PConnection> it = connections.iterator();
		while(it.hasNext()){
			P2PConnection con = it.next();
			con.disconnect();
		}
		adapter.notifyDataSetChanged();
	}
	
	//this is the callback when an android framework call is not successful
	public void onFailure(int arg0) {
		String text = "Unsuccessful action: ";
		if(arg0 == WifiP2pManager.P2P_UNSUPPORTED)
			text = text + " unsupported";
		if(arg0 == WifiP2pManager.ERROR)
			text = text + " error";
		if(arg0 == WifiP2pManager.BUSY)
			text = text + " busy";
		
		//appendToConsole(text);
	}

	//this is the callback when an android framework call is successful
	public void onSuccess() {
		
	}

	//this is the method that gets called when a broadcast intent is thrown
	//by the android framework that we registered for during initialization
	public void onReceive(Context context, Intent intent) {
    	
    	String action = intent.getAction();
		
    	//thrown after a success full discoverPeers call
		if(action.equals(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)){

			//get the peer list via the onPeersChanged callback
			appendToConsole("CMGR: Received peers-changed-action");
	    	p2pmgr.requestPeers(chnl,this);
	    	
	    //thrown when a connection is established/broken
	    }else if(action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)){

			//appendToConsole("CMGR: Received connection-changed-action");
	    
			//grab the network info object and check if a connection was established
	    	NetworkInfo networkinfo = (NetworkInfo) intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
	    	if (networkinfo.isConnected()) {
            	p2pmgr.requestConnectionInfo(chnl, this);
            }
	    
	    //this is thrown when the wifi direct state changes on the device
	    }else if(action.equals(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)){

			//appendToConsole("CMGR: Received state-changed-action");
	    	
			//if the state is disabled, then tell the user to turn on the wifi direct and send them to the settings page
			//THIS IS UNRELIABLE
	    	int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
	    	if(state == WifiP2pManager.WIFI_P2P_STATE_DISABLED){
	    		adbldr.setTitle("Wifi Direct Alert");
	    		adbldr.setMessage("Wifi Direct is currently disabled, enable Wifi Direct in Android settings.");
	    		adbldr.setIcon(android.R.drawable.ic_dialog_alert);
	    		adbldr.setPositiveButton("OK", new OnClickListener() {
	    			public void onClick(DialogInterface dialog, int which) {
	    				Intent intent = new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS);
	    				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
	    				cntxt.startActivity(intent);
	    			}
	    		});
	    		adbldr.show();
	    	}
	    	
	    //thrown when device's general settings change?	
	    }else if(action.equals(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)){

			//appendToConsole("CMGR: Received device-changed-action");
	    	
		//thrown every x seconds by the scan alarm we setup during initialization
	    }else if(action.equals("scanAlarm")){
	    	
	    	//if discovery is not on don't do anything
	    	if(!discoveryOn)
	    		return;
	    	
			appendToConsole("CMGR: Received scan alarm");
	    	discoverPeers();
	    	
	    //this is thrown by a P2PConnection object when a connection goes down 
	    //NOT WORKING YET
	    }else if(action.equals("peerDisconnected")){
	    	
	    	/*String peermac = intent.getParcelableExtra("PEER_MAC_ADDRESS");
	    	Iterator<P2PConnection> it = connections.iterator();
	    	while(it.hasNext()){
	    		P2PConnection temp = it.next();
	    		if(temp.dev.deviceAddress.equals(peermac)){
	    			connections.remove(temp);
	    			break;
	    		}
	    			
	    	}*/
	    	
	    //we've received a broadcast intent we didn't handle correctly
	    }else if(action.equals("RestartServer")){
	    	
	    	String rMAC = intent.getStringExtra("rMAC");
	    	
	    	//appendToConsole("CMGR: Received restart server for " + rMAC);

	    	Iterator<P2PConnection> it = connections.iterator();
	    	while(it.hasNext()){
	    		P2PConnection con = it.next();
	    		if(con.isConnected && con.myMAC.equals(rMAC)){
	    			con.startServer();
	    			break;
	    		}
	    	}
	    	
	    }else{
	    
			appendToConsole("CMGR: Received unknown intent " + intent.toString());
	    	
	    }
	}

	//this is the callback from the requestPeers call
	public void onPeersAvailable(WifiP2pDeviceList dlist) {
		
		WifiP2pDevice dev = null;
		WifiP2pConfig cnfg = null;
		
		//create a new P2PConenction object based on a null device
		//set the device when we loop through the peers
		//P2PConnection has overridden the equals method so we can compare two 
		//objects based on their underlying WifiP2PDevice
		P2PConnection tempcon = new P2PConnection(cntxt,chnl,p2pmgr,this,dev,console);
				
		curdlist = dlist;
		Collection<WifiP2pDevice> dcol = dlist.getDeviceList();
		Iterator<WifiP2pDevice> it = dcol.iterator();

		//loop through the devices and check if we have a connection object already
		//TODO: Connection objects remain persistent even after disconnection
		while(it.hasNext()){
			
			dev = it.next();
			tempcon.setDevice(dev);
			if(!connections.contains(tempcon)){
				
				//if the connection list doesn't have a connection object for the given device create one
				P2PConnection c = new P2PConnection(cntxt,chnl,p2pmgr,this,dev,console);
				connections.add(c);	
				adapter.notifyDataSetChanged();
				
				//try connection to new device
				//probabilistic connection so we don't have simultaneous connection attempts
				//UNTESTED - for now lets have user initiate all connections
				if(rnd.nextInt()%10 > 5){
					//cnfg = new WifiP2pConfig();
					//cnfg.deviceAddress = dev.deviceAddress;
					//p2pmgr.connect(chnl, cnfg, c);
					//appendToConsole("CMGR: Trying to connect to peer: " + dev.toString());
				}
				
			}else{
				
				//connection object already exists - check if its state is connected
				P2PConnection c = connections.get(connections.indexOf(tempcon));
				if(!c.isConnected){ //if not connected
					
					//try connection to device
					//probabistic connection so we don't have simultaneous connection attempts
					//UNTESTED - for now lets have user initiate all connections
					if(rnd.nextInt()%10 > 5){
						//cnfg = new WifiP2pConfig();
						//cnfg.deviceAddress = dev.deviceAddress;
						//p2pmgr.connect(chnl, cnfg, c);
						//appendToConsole("CMGR: Trying to connect to peer: " + dev.toString());
					}
				}
			}
				
		}
		
		//update the UI
		adapter.notifyDataSetChanged();	
	}
	
	//this is called when a user clicks on a device in the UI
	public void tryConnection(int indx){
		
		WifiP2pConfig cnfg = null;
		P2PConnection connection = null;
		
		//make sure there's a connection object at given index
		if(connections.get(indx) == null)
			return;
		
		//check if we're already connected
		//if so disconnect, else try connection to device
		if(connections.get(indx).isConnected){
			connections.get(indx).disconnect(); 
		}else{
			connection = connections.get(indx);
			cnfg = new WifiP2pConfig();
			cnfg.deviceAddress = connection.dev.deviceAddress;
			p2pmgr.connect(chnl, cnfg, this);
			appendToConsole("CMGR: Trying to connect to peer: " + connection.dev.deviceAddress + " (" + connection.dev.deviceName + ")");
		}
		
		
	}

	//this is the callback for the getGroupInfo call
	//UNUSED
	public void onGroupInfoAvailable(WifiP2pGroup arg0) {
		appendToConsole("CMGR: Group info available: " + arg0.toString());
	}

	//this is the callback when the channel is disconnected
	//UNUSED
	public void onChannelDisconnected() {
		appendToConsole("CMGR: Channel disconnected ");
	}

	//this is the callback for getConnectionInfo call after connection state changed
	public void onConnectionInfoAvailable(WifiP2pInfo info) {
		    	
    	//here check whether we're the group owner, then create server socket if so
    	if(info.isGroupOwner){
    		
    		appendToConsole("CMGR: Setting up server handshake on " + info.groupOwnerAddress.getHostAddress() + ":5555");
    		
    		//setup the server handshake with the group's IP, port, the device's mac, and the port for the conenction to communicate on
    		ServerHandshake sh = new ServerHandshake();
    		sh.setup(myMAC, info.groupOwnerAddress.getHostAddress(),5555,childportstart);
    		childportstart += 2;
    		sh.execute();
    		
    	}else{
    		
    		//give server a second to setup the server socket
    		try{
    		Thread.sleep(1000);
    		}catch (Exception e){
    			System.out.println(e.toString());
    		}
    		
    		String myIP = "";
    		try {
				Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
				while(en.hasMoreElements()){
					NetworkInterface ni = en.nextElement();
					Enumeration<InetAddress> en2 = ni.getInetAddresses();
					while(en2.hasMoreElements()){
						InetAddress inet = en2.nextElement();
						if(!inet.isLoopbackAddress() && inet instanceof Inet4Address){
							myIP = inet.getHostAddress();
						}
					}
				}
			} catch (SocketException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    		
    		appendToConsole("CMGR: Setting up client handshake from " + myIP + ":5555");
    		
    		//setup the client handshake to connect to the server and trasfer the device's MAC, get port for connection's communication
    		ClientHandshake ch = new ClientHandshake();
    		ch.setup(info.groupOwnerAddress.getHostAddress(),5555,myMAC,myIP);
    		ch.execute();
    		
    	}
		
	}
	
	//this is the callback from the clienthandshake/serverhandshake asynctask
	public void createNewP2PConnection(String myMAC, String myIP, String peerMAC, String peerIP, int childport, int groupowner){
	
		appendToConsole("CMGR: Handshake successful for peer with MAC " + peerMAC + ".");
		
		if(peerMAC != null && !peerMAC.equals("")){
			Iterator<P2PConnection> it = connections.iterator();
			while(it.hasNext()){
				P2PConnection tempcon = it.next();
				if(tempcon.isConnected)
					return;
				
				//the MAC address returned by the WifiP2pDevice differs from the MAC address found
				//by the WifiManager and stored in our temp file - the first two bytes of the one
				//stored in file are a0 (that match the wifi mac found in wifi settings) while the 
				//device's first two bytes start with a2 (maybe another bug?) so we just compare the rest
				if(tempcon.dev.deviceAddress.toString().substring(3).equals(peerMAC.substring(3))){
					
					tempcon.setMyInfo(myMAC,myIP);
					tempcon.setPeerInfo(peerMAC, peerIP, childport, groupowner);
					tempcon.setConnected();
					tempcon.startServer();

					adapter.notifyDataSetChanged();
					Intent i = new Intent("newConnection");
					i.putExtra("peerMAC", peerMAC);
					cntxt.sendBroadcast(i);	
		        	return;
				}
			}
			
			//shouldn't ever get here? bc peers-changed-intent is thrown upon incomming connection
			//and we update our connection list when this is thrown
			return;
	    }
	}
	
	public void sendMessageToPeer(String peerMAC, P2PMessage msg){
		
		Iterator<P2PConnection> it = connections.iterator();
		while(it.hasNext()){
			P2PConnection tempcon = it.next();
			if(tempcon.isConnected && tempcon.dev.deviceAddress.toString().substring(3).equals(peerMAC.substring(3))){
				//appendToConsole("CMGR: Sending message to " + peerMAC);
				tempcon.sendMessage(msg);
				break;
			}
		}
	}
	
	public void appendToConsole(String s){
    	
		console.append(dateFormat.format(new Date()) + " " + s + "\n");
		console.post(new Runnable()
		    {
		        public void run()
		        {
		        	 Layout l = console.getLayout();
		        	 if(l == null)
		        		 return;
		        	 final int scrollAmount = l.getLineTop(console.getLineCount())- console.getHeight();
		        	 if(scrollAmount>0)
		        		 console.scrollTo(0, scrollAmount);
		        	 else
		        		 console.scrollTo(0,0);
		        }
		    });
	}
	
	public String getMyMacAddress(){
		byte[] myMACbytes = new byte[17];
	    try {
	        FileInputStream fis = cntxt.openFileInput("myMACaddress.txt");
	        fis.read(myMACbytes,0,17);
	    } catch (IOException e) {
	        e.printStackTrace();
	        return null;
	    }
	    return new String(myMACbytes);
	}
	
	public void createFileWithString(String filePath, String address) throws java.io.IOException{
		FileOutputStream fos = cntxt.openFileOutput(filePath, Context.MODE_PRIVATE);
		fos.write(address.getBytes());
		fos.close();
	}
	
	public class ClientHandshake extends AsyncTask<Void, Void, String> {
		
		String peerIP;
		String peerMAC;
        int peerPort;
        int childport;
        byte[] buffer;
        byte[] buffer2;
        Socket s;
        OutputStream outs;
        InputStream ins;
        String myMAC;
        String myIP;

        public void setup(String peerIP, int peerPort, String myMAC, String myIP ) {
            this.peerIP = peerIP;
            this.peerPort = peerPort;
            this.myMAC = myMAC;
            this.myIP = myIP;
        }

        public String doInBackground(Void...params) {
			try{
				
				//do socket initialization
	        	s = new Socket();
	        	s.connect(new InetSocketAddress(peerIP,peerPort), 0);
	        	
				outs = s.getOutputStream();
				ins = s.getInputStream();
				
				//write our MAC address
				outs.write(myMAC.getBytes());
				
				//write our IP address len
				buffer = ByteBuffer.allocate(4).putInt(myIP.length()).array();
				outs.write(buffer,0,4);
				
				//write our IP address
				outs.write(myIP.getBytes(),0,myIP.length());
				
				//read the peer's MAC
				buffer = new byte[17];
				ins.read(buffer,0,17);
				peerMAC = new String(buffer);
								
				//get the port we'll talk on
				buffer2 = new byte[4];
				ins.read(buffer2,0,4);
				childport = ByteBuffer.wrap(buffer2).getInt();
				
				s.close();
				
				return peerMAC;
				
			}catch(Exception e){
				e.printStackTrace();
			}
			
			return null;
			
        }
        
        @Override
        public void onPostExecute(String peerMAC){
        	createNewP2PConnection(myMAC,myIP,peerMAC,peerIP,childport,0);
        }

    }
	
	public class ServerHandshake extends AsyncTask<Void, Void, String> {
		
        int portno;
        int childport;
        int peerIPlen;
        byte[] buffer;
        byte[] buffer2;
        Socket s;
        ServerSocket ss;
        InputStream ins;
		OutputStream outs;
        String myMAC;
        String myIP;
        String ipaddr;
        String peerMAC;
        String peerIP;
        int myPort;

        public void setup(String myMAC, String myIP, int myPort, int childport) {
        	this.myIP = myIP;
        	this.myMAC = myMAC;
            this.myPort = myPort;
            this.childport = childport;
        }

        public String doInBackground(Void...params) {
			try{
				//do socket initialization
	        	ss = new ServerSocket();
	        	ss.setReuseAddress(true);
	        	ss.setSoTimeout(0);
	        	ss.bind(new InetSocketAddress(myIP,myPort));
	        	s = ss.accept();
	        	s.setSoTimeout(0);
				ins = s.getInputStream();
				outs = s.getOutputStream();
				
				//read in the peer's MAC
				buffer = new byte[17];
				ins.read(buffer,0,17);
				peerMAC = new String(buffer);
				
				//read in the peer IP's len
				buffer = new byte[4];
				ins.read(buffer,0,4);
				peerIPlen = ByteBuffer.wrap(buffer).getInt();
				
				//read in the peer's IP
				buffer = new byte[peerIPlen];
				ins.read(buffer,0,peerIPlen);
				peerIP = new String(buffer);
				
				//write the local MAC
				outs.write(myMAC.getBytes(),0,myMAC.length());
				
				//write the port to talk on
				buffer2 = ByteBuffer.allocate(4).putInt(childport).array();
				outs.write(buffer2,0,4);
				
				s.close();
				ss.close();
				
				return new String(peerMAC);
				
			}catch(Exception e){
				e.printStackTrace();
			}
			
			return null;
			
        }
        
        @Override
        public void onPostExecute(String peerMAC){
        	createNewP2PConnection(myMAC,myIP,peerMAC,peerIP,childport,1);
        }

    }


}
