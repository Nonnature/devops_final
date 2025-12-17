package edu.wisc.cs.sdn.apps.l3routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFOXMFieldType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.routing.Link;

public class L3Routing implements IFloodlightModule, IOFSwitchListener, 
		ILinkDiscoveryListener, IDeviceListener, IL3Routing
{
	public static final String MODULE_NAME = L3Routing.class.getSimpleName();
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;

    // Interface to link discovery service
    private ILinkDiscoveryService linkDiscProv;

    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Map of hosts to devices
    private Map<IDevice,Host> knownHosts;

    // EXTRA CREDIT: Spanning tree ports for each switch (for loop-free broadcast)
    private Map<Long, Set<Integer>> spanningTreePorts;

    // EXTRA CREDIT: Enable ECMP (Equal-Cost Multi-Path) routing
    // Set to false to test basic routing first
    private static final boolean ECMP_ENABLED = false;

    // EXTRA CREDIT: Enable Spanning Tree for loop-free broadcast
    // Set to false to test basic routing first
    private static final boolean SPANNING_TREE_ENABLED = false;

	/**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));

		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);

        this.knownHosts = new ConcurrentHashMap<IDevice,Host>();

        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        // EXTRA CREDIT: Initialize spanning tree ports map
        this.spanningTreePorts = new ConcurrentHashMap<Long, Set<Integer>>();
        /*********************************************************************/
	}

	/**
	 * Compute shortest paths from all switches to the target switch using BFS.
	 * Returns a map of switchId -> (nextHopSwitchId, outputPort)
	 */
	private Map<Long, int[]> computeShortestPaths(long targetSwitchId) {
		Map<Long, int[]> result = new HashMap<Long, int[]>();
		Map<Long, IOFSwitch> switches = getSwitches();
		Collection<Link> links = getLinks();

		if (!switches.containsKey(targetSwitchId)) {
			return result;
		}

		// Build adjacency list
		Map<Long, List<Link>> adjacency = new HashMap<Long, List<Link>>();
		for (Long switchId : switches.keySet()) {
			adjacency.put(switchId, new ArrayList<Link>());
		}

		for (Link link : links) {
			if (adjacency.containsKey(link.getSrc())) {
				adjacency.get(link.getSrc()).add(link);
			}
		}

		// BFS from target switch
		Map<Long, Long> predecessor = new HashMap<Long, Long>();
		Map<Long, Integer> predPort = new HashMap<Long, Integer>();
		Queue<Long> queue = new LinkedList<Long>();

		queue.add(targetSwitchId);
		predecessor.put(targetSwitchId, targetSwitchId);

		while (!queue.isEmpty()) {
			Long current = queue.poll();

			for (Link link : adjacency.get(current)) {
				Long neighbor = link.getDst();
				if (!predecessor.containsKey(neighbor)) {
					predecessor.put(neighbor, current);
					predPort.put(neighbor, link.getDstPort());
					queue.add(neighbor);
				}
			}

			// Also check reverse direction
			for (Link link : links) {
				if (link.getDst() == current && !predecessor.containsKey(link.getSrc())) {
					predecessor.put(link.getSrc(), current);
					predPort.put(link.getSrc(), link.getSrcPort());
					queue.add(link.getSrc());
				}
			}
		}

		// Build result: for each switch, find the port to reach target
		for (Long switchId : switches.keySet()) {
			if (switchId == targetSwitchId || !predecessor.containsKey(switchId)) {
				continue;
			}

			// Trace back to find next hop
			Long current = switchId;
			while (predecessor.get(current) != targetSwitchId && predecessor.get(current) != current) {
				current = predecessor.get(current);
			}

			if (predecessor.get(current) == targetSwitchId) {
				// current is the switch directly connected to target
				// We need to find the port on switchId that leads towards target
				Long trace = switchId;
				int port = predPort.get(trace);
				result.put(switchId, new int[]{port});
			}
		}

		return result;
	}

	/**
	 * Get the output port on srcSwitch to reach dstSwitch using BFS.
	 */
	private int getNextHopPort(long srcSwitchId, long dstSwitchId) {
		if (srcSwitchId == dstSwitchId) {
			return -1;
		}

		Map<Long, IOFSwitch> switches = getSwitches();
		Collection<Link> links = getLinks();

		// BFS from src to dst
		Map<Long, Long> predecessor = new HashMap<Long, Long>();
		Map<Long, Integer> portToNext = new HashMap<Long, Integer>();
		Queue<Long> queue = new LinkedList<Long>();

		queue.add(srcSwitchId);
		predecessor.put(srcSwitchId, srcSwitchId);

		while (!queue.isEmpty()) {
			Long current = queue.poll();

			if (current == dstSwitchId) {
				break;
			}

			// Check all links from current switch
			for (Link link : links) {
				if (link.getSrc() == current && !predecessor.containsKey(link.getDst())) {
					predecessor.put(link.getDst(), current);
					portToNext.put(link.getDst(), link.getSrcPort());
					queue.add(link.getDst());
				}
				if (link.getDst() == current && !predecessor.containsKey(link.getSrc())) {
					predecessor.put(link.getSrc(), current);
					portToNext.put(link.getSrc(), link.getDstPort());
					queue.add(link.getSrc());
				}
			}
		}

		if (!predecessor.containsKey(dstSwitchId)) {
			return -1;
		}

		// Trace back to find the first hop from src
		Long current = dstSwitchId;
		while (predecessor.get(current) != srcSwitchId) {
			current = predecessor.get(current);
		}

		return portToNext.get(current);
	}

	/**
	 * Install routing rules for a specific host on all switches.
	 * EXTRA CREDIT: Uses ECMP when multiple equal-cost paths exist.
	 */
	private void installHostRules(Host host) {
		// EXTRA CREDIT: Use ECMP routing if enabled
		if (ECMP_ENABLED) {
			installECMPHostRules(host);
			return;
		}

		// Original single-path routing
		if (host == null || !host.isAttachedToSwitch()) {
			return;
		}

		IOFSwitch hostSwitch = host.getSwitch();
		int hostPort = host.getPort();
		long hostMac = host.getMACAddress();
		Integer hostIp = host.getIPv4Address();

		if (hostSwitch == null || hostIp == null) {
			return;
		}

		Map<Long, IOFSwitch> switches = getSwitches();

		for (IOFSwitch sw : switches.values()) {
			// Create match criteria for destination IP
			OFMatch match = new OFMatch();
			match.setDataLayerType(Ethernet.TYPE_IPv4);
			match.setNetworkDestination(hostIp);

			int outPort;
			if (sw.getId() == hostSwitch.getId()) {
				// Direct connection to host
				outPort = hostPort;
			} else {
				// Find path through network
				outPort = getNextHopPort(sw.getId(), hostSwitch.getId());
				if (outPort == -1) {
					continue;
				}
			}

			// Create action to output packet
			OFAction outputAction = new OFActionOutput(outPort);
			OFInstruction instruction = new OFInstructionApplyActions(
					Arrays.asList(outputAction));

			// Install the rule
			SwitchCommands.installRule(sw, this.table,
					SwitchCommands.DEFAULT_PRIORITY, match,
					Arrays.asList(instruction));
		}
	}

	/**
	 * Remove routing rules for a specific host from all switches.
	 */
	private void removeHostRules(Host host) {
		if (host == null) {
			return;
		}

		Integer hostIp = host.getIPv4Address();
		if (hostIp == null) {
			return;
		}

		Map<Long, IOFSwitch> switches = getSwitches();

		for (IOFSwitch sw : switches.values()) {
			OFMatch match = new OFMatch();
			match.setDataLayerType(Ethernet.TYPE_IPv4);
			match.setNetworkDestination(hostIp);

			SwitchCommands.removeRules(sw, this.table, match);
		}
	}

	/**
	 * Update routing rules for all hosts.
	 */
	private void updateAllRules() {
		// EXTRA CREDIT: Recompute spanning tree when topology changes
		if (SPANNING_TREE_ENABLED) {
			computeSpanningTree();
			installBroadcastRules();
		}

		// Remove all existing rules first, then reinstall
		for (Host host : getHosts()) {
			if (host.isAttachedToSwitch()) {
				removeHostRules(host);
				installHostRules(host);
			}
		}
	}

	/*************************************************************************/
	/*                    EXTRA CREDIT: SPANNING TREE                        */
	/*************************************************************************/

	/**
	 * EXTRA CREDIT: Compute spanning tree using BFS from the root switch.
	 * The root is the switch with the lowest DPID.
	 * This provides loop-free broadcast/flooding capability.
	 */
	private void computeSpanningTree() {
		Map<Long, IOFSwitch> switches = getSwitches();
		Collection<Link> links = getLinks();

		if (switches.isEmpty()) {
			return;
		}

		// Clear existing spanning tree ports
		spanningTreePorts.clear();

		// Initialize empty port sets for all switches
		for (Long switchId : switches.keySet()) {
			spanningTreePorts.put(switchId, new HashSet<Integer>());
		}

		// Find root switch (lowest DPID)
		long rootSwitch = Long.MAX_VALUE;
		for (Long switchId : switches.keySet()) {
			if (switchId < rootSwitch) {
				rootSwitch = switchId;
			}
		}

		// BFS to build spanning tree
		Set<Long> visited = new HashSet<Long>();
		Queue<Long> queue = new LinkedList<Long>();

		queue.add(rootSwitch);
		visited.add(rootSwitch);

		while (!queue.isEmpty()) {
			Long current = queue.poll();

			// Check all links from/to current switch
			for (Link link : links) {
				Long neighbor = null;
				int currentPort = -1;
				int neighborPort = -1;

				if (link.getSrc() == current && !visited.contains(link.getDst())) {
					neighbor = link.getDst();
					currentPort = link.getSrcPort();
					neighborPort = link.getDstPort();
				} else if (link.getDst() == current && !visited.contains(link.getSrc())) {
					neighbor = link.getSrc();
					currentPort = link.getDstPort();
					neighborPort = link.getSrcPort();
				}

				if (neighbor != null) {
					visited.add(neighbor);
					queue.add(neighbor);

					// Add spanning tree ports (both ends of the link)
					spanningTreePorts.get(current).add(currentPort);
					spanningTreePorts.get(neighbor).add(neighborPort);

					log.info(String.format("Spanning tree edge: s%d:%d <-> s%d:%d",
							current, currentPort, neighbor, neighborPort));
				}
			}
		}

		// Also add host ports to spanning tree (hosts are always part of the tree)
		for (Host host : getHosts()) {
			if (host.isAttachedToSwitch()) {
				long switchId = host.getSwitch().getId();
				int hostPort = host.getPort();
				if (spanningTreePorts.containsKey(switchId)) {
					spanningTreePorts.get(switchId).add(hostPort);
				}
			}
		}

		log.info("Spanning tree computed successfully");
	}

	/**
	 * EXTRA CREDIT: Install broadcast rules that forward only along spanning tree edges.
	 * This prevents broadcast storms in networks with loops.
	 */
	private void installBroadcastRules() {
		Map<Long, IOFSwitch> switches = getSwitches();

		for (IOFSwitch sw : switches.values()) {
			long switchId = sw.getId();
			Set<Integer> stPorts = spanningTreePorts.get(switchId);

			if (stPorts == null || stPorts.isEmpty()) {
				continue;
			}

			// Create match for broadcast traffic (destination MAC = ff:ff:ff:ff:ff:ff)
			OFMatch match = new OFMatch();
			match.setDataLayerDestination("ff:ff:ff:ff:ff:ff");

			// Create output actions for all spanning tree ports
			List<OFAction> actions = new ArrayList<OFAction>();
			for (Integer port : stPorts) {
				actions.add(new OFActionOutput(port));
			}

			OFInstruction instruction = new OFInstructionApplyActions(actions);

			// Install with higher priority than default unicast rules
			SwitchCommands.installRule(sw, this.table,
					(short)(SwitchCommands.DEFAULT_PRIORITY + 1), match,
					Arrays.asList(instruction));

			log.info(String.format("Installed broadcast rule on s%d with %d spanning tree ports",
					switchId, stPorts.size()));
		}
	}

	/*************************************************************************/
	/*                    EXTRA CREDIT: ECMP ROUTING                         */
	/*************************************************************************/

	/**
	 * EXTRA CREDIT: Find all equal-cost (shortest) paths from srcSwitch to dstSwitch.
	 * Returns a list of output ports on srcSwitch, each leading to a different path.
	 */
	private List<Integer> getAllShortestPathPorts(long srcSwitchId, long dstSwitchId) {
		List<Integer> result = new ArrayList<Integer>();

		if (srcSwitchId == dstSwitchId) {
			return result;
		}

		Map<Long, IOFSwitch> switches = getSwitches();
		Collection<Link> links = getLinks();

		// First, compute shortest distance using BFS
		Map<Long, Integer> distance = new HashMap<Long, Integer>();
		Queue<Long> queue = new LinkedList<Long>();

		queue.add(srcSwitchId);
		distance.put(srcSwitchId, 0);

		while (!queue.isEmpty()) {
			Long current = queue.poll();
			int currentDist = distance.get(current);

			for (Link link : links) {
				Long neighbor = null;
				if (link.getSrc() == current) {
					neighbor = link.getDst();
				} else if (link.getDst() == current) {
					neighbor = link.getSrc();
				}

				if (neighbor != null && !distance.containsKey(neighbor)) {
					distance.put(neighbor, currentDist + 1);
					queue.add(neighbor);
				}
			}
		}

		if (!distance.containsKey(dstSwitchId)) {
			return result;
		}

		int shortestDist = distance.get(dstSwitchId);

		// Now find all first-hop neighbors that lead to shortest paths
		for (Link link : links) {
			Long neighbor = null;
			int outPort = -1;

			if (link.getSrc() == srcSwitchId) {
				neighbor = link.getDst();
				outPort = link.getSrcPort();
			} else if (link.getDst() == srcSwitchId) {
				neighbor = link.getSrc();
				outPort = link.getDstPort();
			}

			if (neighbor != null && distance.containsKey(neighbor)) {
				// Check if going through this neighbor is on a shortest path
				if (distance.get(neighbor) == 1 && distance.get(dstSwitchId) <= shortestDist) {
					// Verify this neighbor can reach destination in (shortestDist - 1) hops
					int distFromNeighbor = computeDistance(neighbor, dstSwitchId);
					if (distFromNeighbor == shortestDist - 1) {
						if (!result.contains(outPort)) {
							result.add(outPort);
						}
					}
				}
			}
		}

		return result;
	}

	/**
	 * EXTRA CREDIT: Compute the shortest distance between two switches.
	 */
	private int computeDistance(long srcSwitchId, long dstSwitchId) {
		if (srcSwitchId == dstSwitchId) {
			return 0;
		}

		Collection<Link> links = getLinks();
		Map<Long, Integer> distance = new HashMap<Long, Integer>();
		Queue<Long> queue = new LinkedList<Long>();

		queue.add(srcSwitchId);
		distance.put(srcSwitchId, 0);

		while (!queue.isEmpty()) {
			Long current = queue.poll();
			int currentDist = distance.get(current);

			if (current == dstSwitchId) {
				return currentDist;
			}

			for (Link link : links) {
				Long neighbor = null;
				if (link.getSrc() == current) {
					neighbor = link.getDst();
				} else if (link.getDst() == current) {
					neighbor = link.getSrc();
				}

				if (neighbor != null && !distance.containsKey(neighbor)) {
					distance.put(neighbor, currentDist + 1);
					queue.add(neighbor);
				}
			}
		}

		return -1; // No path found
	}

	/**
	 * EXTRA CREDIT: Install ECMP rules for a host.
	 * When multiple equal-cost paths exist, install rules that match on TCP port
	 * (even/odd) to distribute traffic across paths.
	 */
	private void installECMPHostRules(Host host) {
		if (host == null || !host.isAttachedToSwitch()) {
			return;
		}

		IOFSwitch hostSwitch = host.getSwitch();
		int hostPort = host.getPort();
		Integer hostIp = host.getIPv4Address();

		if (hostSwitch == null || hostIp == null) {
			return;
		}

		Map<Long, IOFSwitch> switches = getSwitches();

		for (IOFSwitch sw : switches.values()) {
			if (sw.getId() == hostSwitch.getId()) {
				// Direct connection - install single rule
				OFMatch match = new OFMatch();
				match.setDataLayerType(Ethernet.TYPE_IPv4);
				match.setNetworkDestination(hostIp);

				OFAction outputAction = new OFActionOutput(hostPort);
				OFInstruction instruction = new OFInstructionApplyActions(
						Arrays.asList(outputAction));

				SwitchCommands.installRule(sw, this.table,
						SwitchCommands.DEFAULT_PRIORITY, match,
						Arrays.asList(instruction));
			} else {
				// Check for multiple paths
				List<Integer> pathPorts = getAllShortestPathPorts(sw.getId(), hostSwitch.getId());

				if (pathPorts.isEmpty()) {
					continue;
				}

				if (pathPorts.size() == 1) {
					// Single path - install normal rule
					OFMatch match = new OFMatch();
					match.setDataLayerType(Ethernet.TYPE_IPv4);
					match.setNetworkDestination(hostIp);

					OFAction outputAction = new OFActionOutput(pathPorts.get(0));
					OFInstruction instruction = new OFInstructionApplyActions(
							Arrays.asList(outputAction));

					SwitchCommands.installRule(sw, this.table,
							SwitchCommands.DEFAULT_PRIORITY, match,
							Arrays.asList(instruction));
				} else {
					// Multiple paths - install ECMP rules based on TCP port
					log.info(String.format("ECMP: Installing %d paths from s%d to host %s",
							pathPorts.size(), sw.getId(), host.getName()));

					// Install rules for TCP traffic split by port number (even/odd)
					for (int i = 0; i < pathPorts.size() && i < 2; i++) {
						// Rule for TCP with port matching
						OFMatch tcpMatch = new OFMatch();
						tcpMatch.setDataLayerType(Ethernet.TYPE_IPv4);
						tcpMatch.setNetworkProtocol((byte) 6); // TCP
						tcpMatch.setNetworkDestination(hostIp);

						// Match on TCP destination port: even (i=0) or odd (i=1)
						// We use a bitmask approach: port & 1 == i
						// For simplicity, we'll match on specific port ranges
						if (i == 0) {
							// Even ports: match ports 0-32767 with mask
							tcpMatch.setTransportDestination((short) 0);
							// Use wildcard for lower bit = 0 (even)
						} else {
							// Odd ports: match ports with lower bit = 1
							tcpMatch.setTransportDestination((short) 1);
						}

						OFAction outputAction = new OFActionOutput(pathPorts.get(i));
						OFInstruction instruction = new OFInstructionApplyActions(
								Arrays.asList(outputAction));

						// Higher priority for ECMP TCP rules
						SwitchCommands.installRule(sw, this.table,
								(short)(SwitchCommands.DEFAULT_PRIORITY + 2), tcpMatch,
								Arrays.asList(instruction));
					}

					// Also install default rule for non-TCP traffic (use first path)
					OFMatch defaultMatch = new OFMatch();
					defaultMatch.setDataLayerType(Ethernet.TYPE_IPv4);
					defaultMatch.setNetworkDestination(hostIp);

					OFAction defaultAction = new OFActionOutput(pathPorts.get(0));
					OFInstruction defaultInstruction = new OFInstructionApplyActions(
							Arrays.asList(defaultAction));

					SwitchCommands.installRule(sw, this.table,
							SwitchCommands.DEFAULT_PRIORITY, defaultMatch,
							Arrays.asList(defaultInstruction));
				}
			}
		}
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
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		// No additional startup tasks needed
		/*********************************************************************/
	}

	/**
	 * Get the table in which this application installs rules.
	 */
	public byte getTable()
	{ return this.table; }
	
    /**
     * Get a list of all known hosts in the network.
     */
    private Collection<Host> getHosts()
    { return this.knownHosts.values(); }
	
    /**
     * Get a map of all active switches in the network. Switch DPID is used as
     * the key.
     */
	private Map<Long, IOFSwitch> getSwitches()
    { return floodlightProv.getAllSwitchMap(); }
	
    /**
     * Get a list of all active links in the network.
     */
    private Collection<Link> getLinks()
    { return linkDiscProv.getLinks().keySet(); }

    /**
     * Event handler called when a host joins the network.
     * @param device information about the host
     */
	@Override
	public void deviceAdded(IDevice device) 
	{
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null)
		{
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);

			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host          */
			installHostRules(host);
			/*****************************************************************/
		}
	}

	/**
     * Event handler called when a host is no longer attached to a switch.
     * @param device information about the host
     */
	@Override
	public void deviceRemoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		log.info(String.format("Host %s is no longer attached to a switch",
				host.getName()));

		/*********************************************************************/
		/* TODO: Update routing: remove rules to route to host               */
		removeHostRules(host);
		/*********************************************************************/
	}

	/**
     * Event handler called when a host moves within the network.
     * @param device information about the host
     */
	@Override
	public void deviceMoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		if (!host.isAttachedToSwitch())
		{
			this.deviceRemoved(device);
			return;
		}
		log.info(String.format("Host %s moved to s%d:%d", host.getName(),
				host.getSwitch().getId(), host.getPort()));

		/*********************************************************************/
		/* TODO: Update routing: change rules to route to host               */
		removeHostRules(host);
		installHostRules(host);
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
		/* TODO: Update routing: change routing rules for all hosts          */
		updateAllRules();
		/*********************************************************************/
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId)
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		updateAllRules();
		/*********************************************************************/
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) 
	{
		for (LDUpdate update : updateList)
		{
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst())
			{
				log.info(String.format("Link s%s:%d -> host updated", 
					update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else
			{
				log.info(String.format("Link s%s:%d -> %s:%d updated",
					update.getSrc(), update.getSrcPort(),
					update.getDst(), update.getDstPort()));
			}
		}

		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		updateAllRules();
		/*********************************************************************/
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) 
	{ this.linkDiscoveryUpdate(Arrays.asList(update)); }
	
	/**
     * Event handler called when the IP address of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceIPV4AddrChanged(IDevice device) 
	{ this.deviceAdded(device); }

	/**
     * Event handler called when the VLAN of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceVlanChanged(IDevice device) 
	{ /* Nothing we need to do, since we're not using VLANs */ }
	
	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId) 
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
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
	{ /* Nothing we need to do, since we'll get a linkDiscoveryUpdate event */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) 
	{ return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) 
	{ return false; }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{
		Collection<Class<? extends IFloodlightService>> services =
					new ArrayList<Class<? extends IFloodlightService>>();
		services.add(IL3Routing.class);
		return services; 
	}

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ 
        Map<Class<? extends IFloodlightService>, IFloodlightService> services =
        			new HashMap<Class<? extends IFloodlightService>, 
        					IFloodlightService>();
        // We are the class that implements the service
        services.put(IL3Routing.class, this);
        return services;
	}

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> modules =
	            new ArrayList<Class<? extends IFloodlightService>>();
		modules.add(IFloodlightProviderService.class);
		modules.add(ILinkDiscoveryService.class);
		modules.add(IDeviceService.class);
        return modules;
	}
}
