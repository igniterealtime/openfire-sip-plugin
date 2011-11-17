package org.jivesoftware.openfire.plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.XPath;
import org.dom4j.tree.DefaultAttribute;
import org.dom4j.tree.DefaultElement;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.plugin.packageProcessor.AbstractRemoteRosterProcessor;
import org.jivesoftware.openfire.plugin.packageProcessor.ReceiveComponentUpdatesProcessor;
import org.jivesoftware.openfire.plugin.packageProcessor.SendRosterProcessor;
import org.jivesoftware.openfire.roster.RosterManager;
import org.jivesoftware.openfire.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.IQ;
import org.xmpp.packet.Packet;

public class RemotePackageInterceptor implements PacketInterceptor {

	private static final Logger Log = LoggerFactory.getLogger(RemoteRosterPlugin.class);
	private String _mySubdomain;
	private Map<String, AbstractRemoteRosterProcessor> _packetProcessor = new HashMap<String, AbstractRemoteRosterProcessor>();
	private Set<String> _registeredJids = new HashSet<String>();

	public RemotePackageInterceptor(String initialSubdomain) {

		_mySubdomain = initialSubdomain;
		XMPPServer server = XMPPServer.getInstance();
		RosterManager rosterMananger = server.getRosterManager();
		AbstractRemoteRosterProcessor sendroster = new SendRosterProcessor(rosterMananger, _mySubdomain);
		AbstractRemoteRosterProcessor receiveChanges = new ReceiveComponentUpdatesProcessor(rosterMananger);
		_packetProcessor.put("sendRoster", sendroster);
		_packetProcessor.put("receiveChanges", receiveChanges);
	}

	@Override
	public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed)
			throws PacketRejectedException
	{
		if (!processed && incoming) {
			if (packet instanceof IQ) {
				IQ myPacket = (IQ) packet;
				if (myPacket.getFrom() == null || myPacket.getTo() == null) {
					return;
				}
				String to = myPacket.getTo().toString();
				String from = myPacket.getFrom().toString();

				if (myPacket.getType().equals(IQ.Type.get) && from.equals(_mySubdomain)) {
					if (findNodesInDocument(myPacket.getElement().getDocument(), "//roster:*").size() == 1) {
						// This Package is a roster request by remote component
						_packetProcessor.get("sendRoster").process(packet);
					}
				} else if (myPacket.getType().equals(IQ.Type.set) && from.equals(_mySubdomain)) {
					if (findNodesInDocument(myPacket.getElement().getDocument(), "//roster:item").size() >= 1) {
						// Component sends roster update
						_packetProcessor.get("receiveChanges").process(packet);
					}
				} else if (myPacket.getType().equals(IQ.Type.set) && myPacket.getTo().toString().equals(_mySubdomain)) {
					// user provided register informations to gateway
					if (packet.toXML().contains("jabber:iq:gateway:register")) {
						_registeredJids.add(from);
					} else if (findNodesInDocument(myPacket.getChildElement().getDocument(), "//register:remove")
							.size() == 1) {
						// TODO: works until the server restarts...:(
						_registeredJids.remove(from);
					}

				} else if (myPacket.getType().equals(IQ.Type.result)
						&& myPacket.getFrom().toString().equals(_mySubdomain)) {
					// Adds iq:registered to features to indicate, that user is
					// registered with gateway
					if (_registeredJids.contains(to)) {
						Element feature = new DefaultElement("feature");
						feature.add(new DefaultAttribute("var", "jabber:iq:registered"));
						myPacket.getChildElement().add(feature);
					}
				}

			}
		}

	}

	/**
	 * Search the specified document for Nodes corresponding to the xpath Keep
	 * in mind that you have to use xmpp namespace for searching e.g.
	 * '//roster:features'
	 * 
	 * @param doc
	 *            document
	 * @param xpath
	 *            with roster namespace for searching in query nodes
	 * @return list of nodes
	 */
	protected List<Node> findNodesInDocument(Document doc, String xpath)
	{
		Map<String, String> namespaceUris = new HashMap<String, String>();
		namespaceUris.put("roster", "jabber:iq:roster");
		namespaceUris.put("register", "jabber:iq:register");
		XPath xPath = DocumentHelper.createXPath(xpath);
		xPath.setNamespaceURIs(namespaceUris);
		return xPath.selectNodes(doc);
	}

	protected String getServerNameFromComponentName(String componentName)
	{
		int intServer = componentName.lastIndexOf(".");
		return componentName.substring(0, intServer);

	}

}