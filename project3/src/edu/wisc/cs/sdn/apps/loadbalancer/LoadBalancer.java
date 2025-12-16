package edu.wisc.cs.sdn.apps.loadbalancer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFOXMFieldType;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.instruction.OFInstructionGotoTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.l3routing.IL3Routing;
import edu.wisc.cs.sdn.apps.util.ArpServer;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.util.MACAddress;

public class LoadBalancer implements IFloodlightModule, IOFSwitchListener,
		IOFMessageListener
{
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();
	
	private static final byte TCP_FLAG_SYN = 0x02;
	
	private static final short IDLE_TIMEOUT = 20;
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;
    
    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Interface to L3Routing application
    private IL3Routing l3RoutingApp;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{ 
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        this.l3RoutingApp = context.getServiceImpl(IL3Routing.class);
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        
        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
		/*********************************************************************/
	}
	
	/**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override
	public void switchAdded(long switchId)
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));

		/*********************************************************************/
		/* TODO: Install rules to send:                                      */
		/*       (1) packets from new connections to each virtual load       */
		/*       balancer IP to the controller                               */
		/*       (2) ARP packets to the controller, and                      */
		/*       (3) all other packets to the next rule table in the switch  */

		// Rule (3): Default rule - send all packets to next table (lowest priority)
		OFMatch defaultMatch = new OFMatch();
		OFInstruction gotoTable = new OFInstructionGotoTable(l3RoutingApp.getTable());
		SwitchCommands.installRule(sw, this.table,
				(short)(SwitchCommands.DEFAULT_PRIORITY - 1),
				defaultMatch, Arrays.asList(gotoTable));

		// For each virtual IP
		for (Integer virtualIP : instances.keySet()) {
			LoadBalancerInstance instance = instances.get(virtualIP);

			// Rule (1): Send TCP packets to virtual IP to controller
			OFMatch tcpMatch = new OFMatch();
			tcpMatch.setDataLayerType(Ethernet.TYPE_IPv4);
			tcpMatch.setNetworkProtocol(IPv4.PROTOCOL_TCP);
			tcpMatch.setNetworkDestination(virtualIP);

			OFAction outputController = new OFActionOutput(OFPort.OFPP_CONTROLLER);
			OFInstruction tcpInstruction = new OFInstructionApplyActions(
					Arrays.asList(outputController));
			SwitchCommands.installRule(sw, this.table,
					SwitchCommands.DEFAULT_PRIORITY, tcpMatch,
					Arrays.asList(tcpInstruction));

			// Rule (2): Send ARP requests for virtual IP to controller
			OFMatch arpMatch = new OFMatch();
			arpMatch.setDataLayerType(Ethernet.TYPE_ARP);
			arpMatch.setNetworkDestination(virtualIP);

			OFAction arpOutputController = new OFActionOutput(OFPort.OFPP_CONTROLLER);
			OFInstruction arpInstruction = new OFInstructionApplyActions(
					Arrays.asList(arpOutputController));
			SwitchCommands.installRule(sw, this.table,
					SwitchCommands.DEFAULT_PRIORITY, arpMatch,
					Arrays.asList(arpInstruction));
		}
		/*********************************************************************/
	}
	
	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) 
	{
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;
		
		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0,
				pktIn.getPacketData().length);

		/*********************************************************************/
		/* TODO: Send an ARP reply for ARP requests for virtual IPs; for TCP */
		/*       SYNs sent to a virtual IP, select a host and install        */
		/*       connection-specific rules to rewrite IP and MAC addresses;  */
		/*       for all other TCP packets sent to a virtual IP, send a TCP  */
		/*       reset; ignore all other packets                             */

		// Handle ARP packets
		if (ethPkt.getEtherType() == Ethernet.TYPE_ARP) {
			ARP arpPkt = (ARP) ethPkt.getPayload();

			// Check if it's an ARP request for a virtual IP
			int targetIP = IPv4.toIPv4Address(arpPkt.getTargetProtocolAddress());
			if (arpPkt.getOpCode() == ARP.OP_REQUEST && instances.containsKey(targetIP)) {
				LoadBalancerInstance instance = instances.get(targetIP);

				// Create ARP reply
				Ethernet arpReply = new Ethernet();
				arpReply.setEtherType(Ethernet.TYPE_ARP);
				arpReply.setSourceMACAddress(instance.getVirtualMAC());
				arpReply.setDestinationMACAddress(ethPkt.getSourceMACAddress());

				ARP arpReplyPayload = new ARP();
				arpReplyPayload.setHardwareType(ARP.HW_TYPE_ETHERNET);
				arpReplyPayload.setProtocolType(ARP.PROTO_TYPE_IP);
				arpReplyPayload.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
				arpReplyPayload.setProtocolAddressLength((byte) 4);
				arpReplyPayload.setOpCode(ARP.OP_REPLY);
				arpReplyPayload.setSenderHardwareAddress(instance.getVirtualMAC());
				arpReplyPayload.setSenderProtocolAddress(targetIP);
				arpReplyPayload.setTargetHardwareAddress(arpPkt.getSenderHardwareAddress());
				arpReplyPayload.setTargetProtocolAddress(arpPkt.getSenderProtocolAddress());

				arpReply.setPayload(arpReplyPayload);

				// Send the ARP reply
				SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), arpReply);
			}
			return Command.CONTINUE;
		}

		// Handle IPv4 packets
		if (ethPkt.getEtherType() == Ethernet.TYPE_IPv4) {
			IPv4 ipPkt = (IPv4) ethPkt.getPayload();

			// Check if it's TCP and destined for a virtual IP
			if (ipPkt.getProtocol() == IPv4.PROTOCOL_TCP) {
				int dstIP = ipPkt.getDestinationAddress();
				if (instances.containsKey(dstIP)) {
					TCP tcpPkt = (TCP) ipPkt.getPayload();
					LoadBalancerInstance instance = instances.get(dstIP);

					// Check if it's a TCP SYN
					if (tcpPkt.getFlags() == TCP_FLAG_SYN) {
						// Select next host in round-robin
						int hostIP = instance.getNextHostIP();
						byte[] hostMAC = getHostMACAddress(hostIP);

						if (hostMAC == null) {
							return Command.CONTINUE;
						}

						// Install connection-specific rules
						int srcIP = ipPkt.getSourceAddress();
						short srcPort = tcpPkt.getSourcePort();
						short dstPort = tcpPkt.getDestinationPort();

						// Rule for client -> server: rewrite destination IP and MAC
						OFMatch clientToServerMatch = new OFMatch();
						clientToServerMatch.setDataLayerType(Ethernet.TYPE_IPv4);
						clientToServerMatch.setNetworkProtocol(IPv4.PROTOCOL_TCP);
						clientToServerMatch.setNetworkSource(srcIP);
						clientToServerMatch.setNetworkDestination(dstIP);
						clientToServerMatch.setTransportSource(srcPort);
						clientToServerMatch.setTransportDestination(dstPort);

						List<OFAction> clientToServerActions = new ArrayList<OFAction>();
						clientToServerActions.add(new OFActionSetField(
								OFOXMFieldType.ETH_DST, hostMAC));
						clientToServerActions.add(new OFActionSetField(
								OFOXMFieldType.IPV4_DST, hostIP));

						List<OFInstruction> clientToServerInstructions = new ArrayList<OFInstruction>();
						clientToServerInstructions.add(new OFInstructionApplyActions(clientToServerActions));
						clientToServerInstructions.add(new OFInstructionGotoTable(l3RoutingApp.getTable()));

						SwitchCommands.installRule(sw, this.table,
								(short) (SwitchCommands.DEFAULT_PRIORITY + 1),
								clientToServerMatch, clientToServerInstructions,
								SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);

						// Rule for server -> client: rewrite source IP and MAC
						OFMatch serverToClientMatch = new OFMatch();
						serverToClientMatch.setDataLayerType(Ethernet.TYPE_IPv4);
						serverToClientMatch.setNetworkProtocol(IPv4.PROTOCOL_TCP);
						serverToClientMatch.setNetworkSource(hostIP);
						serverToClientMatch.setNetworkDestination(srcIP);
						serverToClientMatch.setTransportSource(dstPort);
						serverToClientMatch.setTransportDestination(srcPort);

						List<OFAction> serverToClientActions = new ArrayList<OFAction>();
						serverToClientActions.add(new OFActionSetField(
								OFOXMFieldType.ETH_SRC, instance.getVirtualMAC()));
						serverToClientActions.add(new OFActionSetField(
								OFOXMFieldType.IPV4_SRC, dstIP));

						List<OFInstruction> serverToClientInstructions = new ArrayList<OFInstruction>();
						serverToClientInstructions.add(new OFInstructionApplyActions(serverToClientActions));
						serverToClientInstructions.add(new OFInstructionGotoTable(l3RoutingApp.getTable()));

						SwitchCommands.installRule(sw, this.table,
								(short) (SwitchCommands.DEFAULT_PRIORITY + 1),
								serverToClientMatch, serverToClientInstructions,
								SwitchCommands.NO_TIMEOUT, IDLE_TIMEOUT);

					} else {
						// Not a SYN - send TCP reset
						Ethernet tcpReset = new Ethernet();
						tcpReset.setEtherType(Ethernet.TYPE_IPv4);
						tcpReset.setSourceMACAddress(ethPkt.getDestinationMACAddress());
						tcpReset.setDestinationMACAddress(ethPkt.getSourceMACAddress());

						IPv4 ipReset = new IPv4();
						ipReset.setTtl((byte) 64);
						ipReset.setProtocol(IPv4.PROTOCOL_TCP);
						ipReset.setSourceAddress(ipPkt.getDestinationAddress());
						ipReset.setDestinationAddress(ipPkt.getSourceAddress());

						TCP tcpResetPkt = new TCP();
						tcpResetPkt.setSourcePort(tcpPkt.getDestinationPort());
						tcpResetPkt.setDestinationPort(tcpPkt.getSourcePort());
						tcpResetPkt.setSequence(tcpPkt.getAcknowledge());
						tcpResetPkt.setAcknowledge(tcpPkt.getSequence() + 1);
						tcpResetPkt.setFlags((short) 0x14); // RST + ACK
						tcpResetPkt.setWindowSize((short) 0);

						ipReset.setPayload(tcpResetPkt);
						tcpReset.setPayload(ipReset);

						SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), tcpReset);
					}
				}
			}
		}
		/*********************************************************************/

		return Command.CONTINUE;
	}
	
	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress)
	{
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{ /* Nothing we need to do, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId)
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{ return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> floodlightService =
	            new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) 
	{
		return (OFType.PACKET_IN == type 
				&& (name.equals(ArpServer.MODULE_NAME) 
					|| name.equals(DeviceManagerImpl.MODULE_NAME))); 
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) 
	{ return false; }
}
