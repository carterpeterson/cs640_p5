package edu.wisc.cs.sdn.apps.l3routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Queue;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.instruction.OFInstruction;

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
import net.floodlightcontroller.routing.Link;

public class L3Routing implements IFloodlightModule, IOFSwitchListener, 
		ILinkDiscoveryListener, IDeviceListener
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
    public static byte table;
    
    // Map of hosts to devices
    private Map<IDevice,Host> knownHosts;

	/**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String,String> config = context.getConfigParams(this);
		table = Byte.parseByte(config.get("table"));
		this.floodlightProv = context.getServiceImpl(
							     IFloodlightProviderService.class);
		this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
		this.deviceProv = context.getServiceImpl(IDeviceService.class);
		this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
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
		/* TODO: Initialize variables or perform startup tasks, if necessary */
		
		/*********************************************************************/
	}
	
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

    private Collection<Link> getUniqueLinks() {
	Collection<Link> allLinks = this.getLinks();
	Collection<Link> uniqueLinks = new ArrayList<Link>();

	for(Link link : allLinks) {
	    boolean newLink = true;

	    for(Link currentUnique : uniqueLinks) {
		if(((link.getSrc() == currentUnique.getSrc()) && (link.getDst() == currentUnique.getDst())) ||
		   ((link.getDst() == currentUnique.getSrc()) && (link.getSrc() == currentUnique.getDst()))) {
		    newLink = false;
		    break;
		}
	    }

	    if(newLink)
		uniqueLinks.add(link);
	}

	return uniqueLinks;
    }

    private Collection<Link> getOutboundLinks(long baseSwitchId, Collection<Link> links) {
	Collection<Link> connections = new ArrayList<Link>();

	for(Link link : links) {
	    if(link.getSrc() == baseSwitchId || link.getDst() == baseSwitchId)
		connections.add(link);
	}

	return connections;
    }

    private Map<Long, Integer> getBestRoutesToHost(IOFSwitch originSwitch) {
	Map<Long, Integer> predecessors = new ConcurrentHashMap<Long, Integer>();
	Map<Long, Integer> distances = new ConcurrentHashMap<Long, Integer>();
	Queue<Long> switchQueue = new LinkedList<Long>();

        Collection<IOFSwitch> switches = this.getSwitches().values();
	Collection<Link> links = this.getUniqueLinks();

	for (IOFSwitch curSwitch : switches) {
	    if(curSwitch.getId() == originSwitch.getId())
		distances.put(curSwitch.getId(), 0);
	    else
		distances.put(curSwitch.getId(), -1);
	}

	for (int pass = 0; pass < switches.size(); pass++) {
	    links = this.getUniqueLinks();
	    switchQueue.add(originSwitch.getId());
	    
	    while(!switchQueue.isEmpty()) {
		long currentSwitchId = switchQueue.remove();
		Collection<Link> outboundLinks = this.getOutboundLinks(currentSwitchId, links);
	    
		for(Link outboundLink : outboundLinks) {
		    int curSwitchDistance = distances.get(currentSwitchId);
		    int nextSwitchDistance = -1;
		    if(currentSwitchId == outboundLink.getSrc()) { // Switch is on source end of link
			nextSwitchDistance = distances.get(outboundLink.getDst());

			if(nextSwitchDistance == -1 || nextSwitchDistance > (curSwitchDistance + 1)) {
			    distances.put(outboundLink.getDst(), (curSwitchDistance + 1));
			    predecessors.put(outboundLink.getDst(), outboundLink.getDstPort());
			}

			switchQueue.add(outboundLink.getDst());
		    } else { // Switch is on destination end of link
			nextSwitchDistance = distances.get(outboundLink.getSrc());

			if(nextSwitchDistance == -1 || nextSwitchDistance > (curSwitchDistance + 1)) {
			    distances.put(outboundLink.getSrc(), (curSwitchDistance + 1));
			    predecessors.put(outboundLink.getSrc(), outboundLink.getSrcPort());
			}

			switchQueue.add(outboundLink.getSrc());
		    }

		    links.remove(outboundLink);
		}

	    }
	}	

	return predecessors;
    }

    private void addRulesForHost(Host host) {
	// Switch ID, outbound port to get packets back to host
	if(host.getSwitch() == null)
	    return;

	Map<Long, Integer> switchRoutes = getBestRoutesToHost(host.getSwitch());
	Set<Long> switchIds = switchRoutes.keySet();
	
	OFMatch matchRule = new OFMatch();
	matchRule.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
	matchRule.setNetworkDestination(host.getIPv4Address());
	
	// Add rules for all switches other than one host is connected to
	for (Long switchId : switchIds) {
	    OFAction outputAction = new OFActionOutput(switchRoutes.get(switchId));
	    OFInstruction actions = 
		new OFInstructionApplyActions(Arrays.asList(outputAction));
	    SwitchCommands.installRule(this.getSwitches().get(switchId), this.table, 
				       SwitchCommands.DEFAULT_PRIORITY, matchRule, Arrays.asList(actions));
	}
	
	// Add rule for switch host is connected to
	OFAction outputAction = new OFActionOutput(host.getPort());
	OFInstruction action = 
	    new OFInstructionApplyActions(Arrays.asList(outputAction));
	SwitchCommands.installRule(host.getSwitch(), this.table, 
				   SwitchCommands.DEFAULT_PRIORITY, matchRule, Arrays.asList(action));
    }

    private void removeRulesForHost(Host host) {
	Collection<IOFSwitch> switches = this.getSwitches().values();

	OFMatch matchRule = new OFMatch();
	matchRule.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
	matchRule.setNetworkDestination(host.getIPv4Address());

	for(IOFSwitch curSwitch : switches) {
	    SwitchCommands.removeRules(curSwitch, this.table, matchRule);
	}
    }

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
			
			if(!host.isAttachedToSwitch())
			    return;

			this.addRulesForHost(host);
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
		{ return; }
		this.knownHosts.remove(host);
		
		log.info(String.format("Host %s is no longer attached to a switch", 
				host.getName()));
		
		this.removeRulesForHost(host);
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
	        
		this.removeRulesForHost(host);
		this.addRulesForHost(host);
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
		
		for(Host host : this.getHosts()) {
		    this.removeRulesForHost(host);
		    this.addRulesForHost(host);
		}
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
		
		for(Host host : this.getHosts()) {
		    this.removeRulesForHost(host);
		    this.addRulesForHost(host);
		}
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
				log.info(String.format("Link s%s:%d -> s%s:%d updated", 
					update.getSrc(), update.getSrcPort(),
					update.getDst(), update.getDstPort()));
			}
		}
		
		for(Host host : this.getHosts()) {
		    this.removeRulesForHost(host);
		    this.addRulesForHost(host);
		}
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
        floodlightService.add(ILinkDiscoveryService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}
}
