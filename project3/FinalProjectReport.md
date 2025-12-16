# SDN Controller Applications for Layer-3 Routing and Load Balancing

## Final Project Report - Data Communications & Networks (CSCI-GA.2662-001)

**Author:** [Your Name]
**NYU ID:** [Your ID]
**Date:** December 2025

---

## Abstract

This report presents the design and implementation of two Software-Defined Networking (SDN) controller applications using the Floodlight OpenFlow controller. The first application implements Layer-3 shortest-path routing using the Bellman-Ford/BFS algorithm to compute and install optimal forwarding rules across all network switches. The second application implements a distributed TCP load balancer that distributes client connections across multiple backend servers using round-robin scheduling. Both applications were tested on various network topologies using the Mininet network emulator.

---

## 1. Introduction

### 1.1 Background

Software-Defined Networking (SDN) represents a paradigm shift in network architecture by separating the control plane from the data plane. In traditional networks, each switch and router runs its own control plane protocols (ARP, STP, OSPF, etc.) to make forwarding decisions. SDN centralizes this intelligence in a network controller, which has a global view of the network and can make optimal forwarding decisions.

### 1.2 Project Objectives

This project implements two SDN controller applications:

1. **Layer-3 Shortest Path Routing**: Computes and installs shortest-path forwarding rules for all hosts in the network.

2. **Distributed Load Balancer**: Distributes TCP connections across multiple backend servers using address rewriting.

### 1.3 Technologies Used

- **Floodlight Controller**: Java-based OpenFlow controller
- **OpenFlow 1.3**: Protocol for controller-switch communication
- **Mininet**: Network emulator for testing
- **Open vSwitch**: Software OpenFlow switch implementation

---

## 2. Related Work

### 2.1 Traditional Routing Protocols

Traditional routing protocols like OSPF and RIP run distributed algorithms where each router independently computes routes based on information exchanged with neighbors. This approach has limitations:
- Slow convergence after topology changes
- Suboptimal paths due to limited visibility
- Complex configuration and troubleshooting

### 2.2 SDN-Based Routing

SDN-based routing leverages the controller's global view to compute optimal paths. Related work includes:
- **NOX/POX Controllers**: Early SDN controllers with Python-based applications
- **ONOS/OpenDaylight**: Production-grade SDN controllers
- **Google B4**: WAN SDN deployment using centralized traffic engineering

### 2.3 Load Balancing Approaches

Traditional load balancing uses hardware appliances (F5, Citrix) or DNS-based distribution. SDN enables:
- Distributed load balancing at network edge
- Fine-grained traffic control
- Dynamic backend server management

---

## 3. Proposed Solution

### 3.1 Architecture Overview

```
+------------------+
|   Floodlight     |
|   Controller     |
+--------+---------+
         |
    OpenFlow Protocol
         |
+--------+---------+--------+--------+
|        |         |        |        |
v        v         v        v        v
+----+ +----+   +----+   +----+   +----+
| S1 | | S2 |...| Sn |   | S1 | | S2 |...
+----+ +----+   +----+   +----+ +----+
  |      |        |        |      |
  H1     H2       Hn       H1     H2
```

### 3.2 Layer-3 Routing Design

**Algorithm**: Breadth-First Search (BFS) for shortest path computation

**Key Components**:
1. **Topology Discovery**: Uses Floodlight's LinkDiscoveryService
2. **Host Discovery**: Uses Floodlight's DeviceService
3. **Path Computation**: BFS from each switch to destination host's switch
4. **Rule Installation**: Proactive installation on all switches

**Flow Rule Strategy**:
- Match: Ethernet Type (IPv4) + Destination IP
- Action: Output to next-hop port
- Installed on ALL switches for each host

### 3.3 Load Balancer Design

**Algorithm**: Round-robin server selection

**Key Components**:
1. **Virtual IP Management**: Map virtual IPs to backend server pools
2. **ARP Handling**: Respond to ARP requests for virtual IPs
3. **Connection Tracking**: Install per-connection rewrite rules
4. **Address Rewriting**: Translate between virtual and real addresses

**Multi-Table Design**:
- Table 0: Load balancer rules (intercept, rewrite)
- Table 1: L3 routing rules (forward)

---

## 4. Implementation

### 4.1 L3Routing Module

**File**: `L3Routing.java`

**Core Methods**:

```java
// Compute next hop port using BFS
private int getNextHopPort(long srcSwitchId, long dstSwitchId) {
    // BFS traversal from source to destination
    // Returns output port on source switch
}

// Install rules for a host on all switches
private void installHostRules(Host host) {
    for (IOFSwitch sw : getSwitches()) {
        // Create match: IPv4 + dst_ip
        // Create action: output to next hop
        // Install rule
    }
}
```

**Event Handling**:
- `deviceAdded`: Install rules for new host
- `deviceRemoved`: Remove rules for host
- `switchAdded/Removed`: Recompute all paths
- `linkDiscoveryUpdate`: Recompute affected paths

### 4.2 LoadBalancer Module

**File**: `LoadBalancer.java`

**Core Methods**:

```java
// Install base rules when switch joins
public void switchAdded(long switchId) {
    // Rule 1: TCP to virtual IP -> controller
    // Rule 2: ARP for virtual IP -> controller
    // Rule 3: Default -> goto L3Routing table
}

// Handle packets sent to controller
public Command receive(IOFSwitch sw, OFMessage msg, ...) {
    if (ARP request for virtual IP) {
        // Send ARP reply with virtual MAC
    }
    if (TCP SYN to virtual IP) {
        // Select backend server (round-robin)
        // Install bidirectional rewrite rules
    }
    if (TCP non-SYN to virtual IP) {
        // Send TCP RST
    }
}
```

---

## 5. Testing and Results

### 5.1 Test Environment

- **Host OS**: Ubuntu 14.04 LTS (VirtualBox VM)
- **Controller**: Floodlight with custom modules
- **Network**: Mininet emulated topologies

### 5.2 L3Routing Test Results

| Topology | Hosts | Switches | Pingall Result |
|----------|-------|----------|----------------|
| single,3 | 3 | 1 | 0% loss |
| linear,3 | 3 | 3 | 0% loss |
| tree,2 | 4 | 3 | 0% loss |
| triangle | 3 | 3 | 0% loss |
| someloops | 6 | 6 | 0% loss |
| mesh,5 | 5 | 5 | 0% loss |
| assign1 | 10 | 6 | 0% loss |

### 5.3 Load Balancer Test Results

| Test | Expected | Actual |
|------|----------|--------|
| ARP for VIP | Reply with virtual MAC | PASS |
| First HTTP request | Routed to server 1 | PASS |
| Second HTTP request | Routed to server 2 | PASS |
| Third HTTP request | Routed to server 1 | PASS |
| Connection timeout | Rules removed after 20s | PASS |

### 5.4 Link Failure Recovery

Test on triangle topology:
1. Initial state: All paths working
2. `link s1 s2 down`: Traffic reroutes via s3
3. `link s1 s2 up`: Original paths restored

---

## 6. Extra Credit Implementation

### 6.1 Spanning Tree for Loop-Free Broadcast (10 points)

**Objective**: Implement flooding without loops by calculating and installing a spanning tree for broadcasts.

**Implementation**:

```java
// Compute spanning tree using BFS from root switch (lowest DPID)
private void computeSpanningTree() {
    // Find root switch (lowest DPID)
    // BFS traversal to build spanning tree
    // Track ports on each switch that are part of the tree
}

// Install broadcast rules using spanning tree ports
private void installBroadcastRules() {
    // Match: dl_dst = ff:ff:ff:ff:ff:ff
    // Action: Output to all spanning tree ports
}
```

**Key Features**:
- Automatic root election (lowest switch DPID)
- BFS-based tree construction
- Broadcast rules with higher priority than unicast
- Host ports automatically included in tree

### 6.2 ECMP (Equal-Cost Multi-Path) Routing (10 points)

**Objective**: Implement ECMP on networks with multiple paths, distributing traffic based on TCP ports.

**Implementation**:

```java
// Find all output ports leading to shortest paths
private List<Integer> getAllShortestPathPorts(long src, long dst) {
    // BFS to compute distances
    // Find all neighbors on shortest paths
}

// Install ECMP rules with TCP port matching
private void installECMPHostRules(Host host) {
    // For TCP with even dst port -> Path 1
    // For TCP with odd dst port -> Path 2
    // Default (non-TCP) -> Path 1
}
```

**Rule Strategy**:
| Traffic Type | TCP Port | Path Used |
|--------------|----------|-----------|
| TCP | Even (0, 2, 4...) | Path 1 |
| TCP | Odd (1, 3, 5...) | Path 2 |
| Non-TCP | N/A | Path 1 |

---

## 7. Discussion

### 7.1 What Went Well

1. **BFS Algorithm**: Simple and effective for shortest path computation
2. **Proactive Rule Installation**: Eliminates packet-in latency for normal traffic
3. **Multi-Table Design**: Clean separation between load balancing and routing
4. **Round-Robin Distribution**: Fair load distribution across servers
5. **Spanning Tree**: Prevents broadcast storms in looped topologies
6. **ECMP**: Utilizes multiple paths for better bandwidth utilization

### 7.2 Challenges Encountered

1. **Topology Change Handling**: Initial implementation caused 100% packet loss when switching topologies without restarting controller
   - **Solution**: Always restart Floodlight and clean Mininet state

2. **Bidirectional Links**: OpenFlow links are unidirectional
   - **Solution**: Check both directions in BFS

3. **Connection State**: No persistent connection tracking
   - **Solution**: Use idle timeout for automatic cleanup

4. **ECMP Path Detection**: Finding all equal-cost paths efficiently
   - **Solution**: BFS for distance computation, then verify each neighbor

### 7.3 Limitations

1. **Scalability**: Full mesh of rules (O(n) rules per switch for n hosts)
2. **No QoS**: All paths treated equally
3. **Single Controller**: No high availability
4. **ECMP Limited to 2 Paths**: Current implementation only uses 2 paths maximum

---

## 8. Security Considerations

### 8.1 Identified Vulnerabilities

**L3Routing**:
- ARP spoofing possible
- No source IP validation
- DoS via rapid topology changes

**LoadBalancer**:
- SYN flood can exhaust flow tables
- Backend server enumeration possible
- Connection hijacking via timeout

### 8.2 Recommended Mitigations

- Implement rate limiting
- Add source IP validation
- Use TLS for controller-switch communication
- Implement connection tracking with longer timeouts

---

## 9. Conclusion

This project successfully implemented two SDN controller applications plus two extra credit features:

1. **L3Routing**: Provides shortest-path forwarding using BFS algorithm, handling all tested topologies including those with loops.

2. **LoadBalancer**: Distributes TCP connections using round-robin scheduling with transparent address rewriting.

3. **Extra Credit - Spanning Tree**: Computes a spanning tree for loop-free broadcast flooding, preventing broadcast storms in looped topologies.

4. **Extra Credit - ECMP**: Distributes traffic across multiple equal-cost paths using TCP port matching (even/odd), improving bandwidth utilization.

The implementation demonstrates key SDN benefits:
- Centralized network intelligence
- Programmatic control of forwarding behavior
- Flexible traffic engineering
- Loop prevention without distributed STP
- Multi-path load balancing

Future work could include:
- Weighted load balancing based on server capacity
- Controller clustering for high availability
- More sophisticated ECMP hashing (5-tuple based)

---

## References

1. McKeown, N., et al. "OpenFlow: Enabling Innovation in Campus Networks." ACM SIGCOMM, 2008.

2. Floodlight Controller Documentation. https://floodlight.atlassian.net/

3. Mininet Documentation. http://mininet.org/

4. Open vSwitch Documentation. http://openvswitch.org/

5. OpenFlow Switch Specification v1.5.1. Open Networking Foundation.

---

## Appendix A: Code Listings

See attached source files:
- `L3Routing.java`
- `LoadBalancer.java`

## Appendix B: Test Logs

See `tests.txt` for detailed testing procedures and results.

## Appendix C: Vulnerability Analysis

See `vulnerabilities.txt` for detailed security analysis.
