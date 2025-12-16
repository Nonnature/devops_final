================================================================================
                        README - DESIGN AND IMPLEMENTATION
                        SDN Final Project - Fall 2025
================================================================================

Project Overview
================================================================================
This project implements two SDN controller applications using the Floodlight
OpenFlow controller framework:

1. Part 3: Layer-3 Shortest Path Routing (L3Routing)
2. Part 4: Distributed Load Balancer (LoadBalancer)

Both applications run on top of the Floodlight controller and manage OpenFlow
switches in a Mininet-emulated network.


================================================================================
PART 3: LAYER-3 SHORTEST PATH ROUTING
================================================================================

Design Overview
---------------
The L3Routing module implements shortest-path forwarding for IPv4 packets
in an SDN network. Unlike traditional routing protocols that run distributed
algorithms on each router, this implementation uses a centralized approach
where the controller computes all paths and installs flow rules proactively.

Key Design Decisions:
1. Use BFS (Breadth-First Search) for shortest path computation
   - All links have equal weight (hop count metric)
   - BFS guarantees shortest path in unweighted graphs
   - Simple and efficient O(V + E) complexity

2. Proactive rule installation
   - Rules installed when hosts are discovered
   - No packet-in to controller for normal traffic
   - Lower latency for data plane forwarding

3. Match on destination IP address
   - Allows routing without MAC learning
   - Works with the ArpServer module for ARP resolution


Implementation Details
----------------------

File: L3Routing.java (edu.wisc.cs.sdn.apps.l3routing)

Key Methods:

1. getNextHopPort(long srcSwitchId, long dstSwitchId)
   - Computes shortest path from source to destination switch
   - Uses BFS traversal of network topology
   - Returns the output port on source switch for next hop
   - Handles bidirectional links by checking both directions

2. installHostRules(Host host)
   - Installs flow rules for reaching a specific host
   - Creates rules on ALL switches in the network
   - Match: Ethernet type = IPv4, Destination IP = host IP
   - Action: Output to appropriate port (direct or via next hop)
   - Uses default priority and no timeout

3. removeHostRules(Host host)
   - Removes flow rules for a specific host from all switches
   - Called when host disconnects or moves

4. updateAllRules()
   - Recomputes and reinstalls rules for all known hosts
   - Called on topology changes (switch/link events)

Event Handlers:
- deviceAdded(): Install rules for new host
- deviceRemoved(): Remove rules for disconnected host
- deviceMoved(): Update rules for moved host
- switchAdded(): Recalculate all routes
- switchRemoved(): Recalculate all routes
- linkDiscoveryUpdate(): Recalculate all routes

Flow Rule Format:
- Table: Specified in l3routing.prop (default: 0)
- Priority: DEFAULT_PRIORITY (1)
- Match: dl_type=0x0800, nw_dst=<host_ip>
- Actions: output:<port>
- Timeout: None (permanent until explicitly removed)


================================================================================
PART 4: DISTRIBUTED LOAD BALANCER
================================================================================

Design Overview
---------------
The LoadBalancer module implements a distributed TCP load balancer that
distributes client connections across multiple backend servers using
round-robin scheduling. It operates transparently by rewriting IP and MAC
addresses in the data plane.

Key Design Decisions:
1. Virtual IP (VIP) abstraction
   - Clients connect to VIP, unaware of actual servers
   - Load balancer handles address translation

2. Connection-based load balancing
   - Each new TCP connection is assigned to a backend server
   - All packets of a connection go to the same server
   - Uses 5-tuple matching for connection identification

3. Distributed implementation
   - Load balancing rules installed in first-hop switch
   - No traffic hairpinning through a central device
   - Leverages L3Routing for actual packet forwarding


Implementation Details
----------------------

File: LoadBalancer.java (edu.wisc.cs.sdn.apps.loadbalancer)

Key Methods:

1. switchAdded(long switchId)
   Installs three types of rules on each switch:

   a) Default rule (lowest priority):
      - Match: Any packet
      - Action: Goto L3Routing table
      - Purpose: Forward non-load-balanced traffic normally

   b) TCP rule for each virtual IP:
      - Match: IPv4, TCP, dst_ip = virtual_ip
      - Action: Send to controller
      - Purpose: Controller handles new connections

   c) ARP rule for each virtual IP:
      - Match: ARP, target_ip = virtual_ip
      - Action: Send to controller
      - Purpose: Controller responds to ARP requests

2. receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx)
   Handles packets sent to controller:

   a) ARP Requests for Virtual IP:
      - Constructs ARP reply with virtual MAC
      - Sends reply back to requesting host

   b) TCP SYN to Virtual IP:
      - Selects backend server (round-robin via getNextHostIP())
      - Gets backend server's MAC address
      - Installs client->server rewrite rule:
        * Match: 5-tuple (src_ip, dst_ip, protocol, src_port, dst_port)
        * Actions: Set dst_mac = server_mac, Set dst_ip = server_ip
        * Then: Goto L3Routing table
      - Installs server->client rewrite rule:
        * Match: 5-tuple (reversed)
        * Actions: Set src_mac = virtual_mac, Set src_ip = virtual_ip
        * Then: Goto L3Routing table
      - Both rules have 20-second idle timeout

   c) Other TCP to Virtual IP (non-SYN):
      - Indicates orphaned packet (connection rules expired)
      - Sends TCP RST to client

Connection-Specific Rule Format:
- Table: 0 (load balancer table)
- Priority: DEFAULT_PRIORITY + 1 (higher than base rules)
- Match: dl_type=0x0800, nw_proto=6, nw_src, nw_dst, tp_src, tp_dst
- Actions: set_field:eth_dst/src, set_field:ipv4_dst/src, goto_table:1
- Idle Timeout: 20 seconds


Multi-Table Design
------------------
The load balancer uses OpenFlow multiple tables:

Table 0 (LoadBalancer):
- Connection-specific rewrite rules (high priority)
- Virtual IP interception rules (default priority)
- Default goto Table 1 rule (low priority)

Table 1 (L3Routing):
- Destination-based forwarding rules
- Handles actual packet delivery after address rewrite


================================================================================
PART 3 EXTRA CREDIT: SPANNING TREE AND ECMP
================================================================================

Extra Credit Feature 1: Spanning Tree (Loop-Free Broadcast)
-----------------------------------------------------------
The L3Routing module computes a spanning tree of the network topology to enable
loop-free broadcast/flooding. This prevents broadcast storms in networks with
loops like triangle and someloops topologies.

Implementation:
- computeSpanningTree(): Uses BFS from root switch (lowest DPID) to build tree
- installBroadcastRules(): Installs rules matching dst_mac=ff:ff:ff:ff:ff:ff
- Only forwards broadcast packets along spanning tree edges
- Host ports are always included in the spanning tree

Key Features:
- Automatic spanning tree computation when topology changes
- Root election based on lowest switch DPID
- Broadcast rules have higher priority than unicast rules


Extra Credit Feature 2: ECMP (Equal-Cost Multi-Path)
----------------------------------------------------
The L3Routing module implements ECMP to distribute traffic across multiple
equal-cost paths. When multiple shortest paths exist between switches, the
module installs rules that match on TCP port to distribute traffic.

Implementation:
- getAllShortestPathPorts(): Finds all output ports leading to shortest paths
- installECMPHostRules(): Installs multiple rules for multi-path routing
- Traffic distribution based on TCP destination port (even/odd)

ECMP Rule Strategy:
- TCP traffic with even destination port -> Path 1
- TCP traffic with odd destination port -> Path 2
- Non-TCP traffic -> Default path (Path 1)
- Higher priority for TCP-specific rules

Configuration:
- ECMP_ENABLED = true (in L3Routing.java) to enable ECMP
- SPANNING_TREE_ENABLED = true (in L3Routing.java) to enable Spanning Tree


Code Attribution
================================================================================
The base code structure was provided by:
- University of Wisconsin (edu.wisc.cs.sdn.apps.loadbalancer)
- Brown University (edu.brown.cs.sdn.apps.sps)

The following code was implemented for this project:
- BFS shortest path algorithm in L3Routing.java
- All event handlers in L3Routing.java (deviceAdded, deviceRemoved, etc.)
- switchAdded() implementation in LoadBalancer.java
- receive() implementation in LoadBalancer.java
- Helper methods: installHostRules(), removeHostRules(), updateAllRules()

Extra Credit implementations:
- computeSpanningTree(): Spanning tree computation using BFS
- installBroadcastRules(): Broadcast rule installation for spanning tree
- getAllShortestPathPorts(): Finding all equal-cost paths
- computeDistance(): Distance calculation between switches
- installECMPHostRules(): ECMP rule installation with TCP port matching


Build and Run Instructions
================================================================================

1. Compile:
   $ cd ~/project3
   $ ant

2. Run with L3Routing only:
   $ java -jar FloodlightWithApps.jar -cf l3routing.prop

3. Run with L3Routing and LoadBalancer:
   $ java -jar FloodlightWithApps.jar -cf loadbalancer.prop

4. Start Mininet (in separate terminal):
   $ sudo ./run_mininet.py <topology>

   Available topologies:
   - single,N: Single switch with N hosts
   - linear,N: N switches in a line, one host each
   - tree,N: Tree of depth N
   - triangle: Triangle topology with 3 switches
   - someloops: Topology with loops
   - mesh,N: Full mesh of N switches
   - assign1: Complex topology

5. Test:
   mininet> pingall
   mininet> h1 curl 10.0.100.1  (for load balancer)


Configuration Files
================================================================================

l3routing.prop:
- Loads L3Routing and ArpServer modules
- L3Routing uses table 0

loadbalancer.prop:
- Loads L3Routing, LoadBalancer, and ArpServer modules
- LoadBalancer uses table 0
- L3Routing uses table 1
- Defines virtual IPs and backend servers:
  * 10.0.100.1 -> 10.0.0.2, 10.0.0.3
  * 10.0.110.1 -> 10.0.0.4, 10.0.0.6
