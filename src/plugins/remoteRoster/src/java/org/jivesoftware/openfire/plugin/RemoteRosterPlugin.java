package org.jivesoftware.openfire.plugin;

import java.io.File;
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
import org.jivesoftware.openfire.SessionManager;
import org.jivesoftware.openfire.component.ComponentEventListener;
import org.jivesoftware.openfire.component.InternalComponentManager;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.session.ComponentSession;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.PropertyEventDispatcher;
import org.jivesoftware.util.PropertyEventListener;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;

public class RemoteRosterPlugin implements Plugin {

//	private static final Logger Log = LoggerFactory.getLogger(RemoteRosterPlugin.class);
	final SessionManager _sessionManager = SessionManager.getInstance();
	private Map<String, RemotePackageInterceptor> _interceptors = new HashMap<String, RemotePackageInterceptor>();
	private static PluginManager pluginManager;
	private Set<String> _waitingForIQResponse = new HashSet<String>();

	private InterceptorManager _interceptorManager;
	private PropertyEventListener _settingsObserver;

	public RemoteRosterPlugin() {

	}

	public void initializePlugin(PluginManager manager, File pluginDirectory)
	{
		pluginManager = manager;
		manageExternalComponents();
		listenToSettings();
		_interceptorManager = InterceptorManager.getInstance();
	}

	private void manageExternalComponents()
	{
		InternalComponentManager compManager = InternalComponentManager.getInstance();
		compManager.addListener(new ComponentEventListener() {
			/**
			 * Check if the unregistered component contains to one of our
			 * package interceptors
			 */
			@Override
			public void componentUnregistered(JID componentJID)
			{

				ComponentSession session = _sessionManager.getComponentSession(componentJID.getDomain());
				if (session != null && _interceptors.containsKey(session.getExternalComponent().getInitialSubdomain())) {
					String initialSubdomain = session.getExternalComponent().getInitialSubdomain();
					// Remove it from Map & ComponentManager
					updateInterceptors(initialSubdomain);
				}
			}

			/**
			 * If there is a new external Component, check if it is a gateway
			 * and add create a package interceptor if it is enabled
			 */
			@Override
			public void componentRegistered(JID componentJID)
			{
				_waitingForIQResponse.add(componentJID.getDomain());
			}

			@Override
			public void componentInfoReceived(IQ iq)
			{
				String from = iq.getFrom().getDomain();
				//Waiting for this external component sending an IQ response to us?
				if (_waitingForIQResponse.contains(from)) {
					Element packet = iq.getChildElement();
					Document doc = packet.getDocument();
					List<Node> nodes = findNodesInDocument(doc, "//xmpp:identity[@category='gateway']");
					//Is this external component a gateway and there is no package interceptor for it?
					if (nodes.size() > 0 && !_interceptors.containsKey(from))
					{
						updateInterceptors(from);
					}
					
					// We got the IQ, we can now remove it from the set, because
					// we are not waiting any more
					_waitingForIQResponse.remove(from);
				}
			}
		});
	}

	
	
	private void listenToSettings()
	{
		_settingsObserver = new RemoteRosterPropertyListener() {
			@Override
			protected void changedProperty(String prop)
			{
				updateInterceptors(prop);
			}
		};
		PropertyEventDispatcher.addListener(_settingsObserver);
	}
	
	public void destroyPlugin()
	{
		for (String interceptor : _interceptors.keySet())
		{
			removeInterceptor(interceptor);
		}
		PropertyEventDispatcher.removeListener(_settingsObserver);
		pluginManager = null;
		_interceptorManager = null;
	}

	
	private void updateInterceptors(String componentJID)
	{
		boolean allowed = JiveGlobals.getBooleanProperty("plugin.remoteroster.jids."+componentJID, false);
		if (allowed)
		{
			if(!_interceptors.containsKey(componentJID))
			{
				createNewPackageIntercetor(componentJID);
			}
		} else
		{
			if(_interceptors.containsKey(componentJID))
			{
				removeInterceptor(componentJID);
			}
		}
	}
	
	public String getName()
	{
		return "remoteRoster";

	}

	/**
	 * Search the specified document for Nodes corresponding to the xpath 
	 * Keep in mind that you have to use xmpp namespace for searching e.g. '//xmpp:features'
	 * @param doc document
	 * @param xpath with xmpp namespace for searching in query nodes
	 * @return list of nodes
	 */
	private List<Node> findNodesInDocument(Document doc, String xpath)
	{
		Map<String, String> namespaceUris = new HashMap<String, String>();
		namespaceUris.put("xmpp", "http://jabber.org/protocol/disco#info");
		XPath xPath = DocumentHelper.createXPath(xpath);
		xPath.setNamespaceURIs(namespaceUris);
		return xPath.selectNodes(doc);
	}
	
	public static PluginManager getPluginManager()
	{
		return pluginManager;
	}

	private void removeInterceptor(String initialSubdomain)
	{
		RemotePackageInterceptor interceptor = _interceptors.get(initialSubdomain);
		if (interceptor != null) {
			_interceptorManager.removeInterceptor(interceptor);
			_interceptors.remove(initialSubdomain);
		}
	}

	private void createNewPackageIntercetor(String initialSubdomain)
	{
		RemotePackageInterceptor interceptor = new RemotePackageInterceptor(initialSubdomain);
		_interceptors.put(initialSubdomain, interceptor);
		_interceptorManager.addInterceptor(interceptor);
	}

}