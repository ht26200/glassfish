/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.flashlight.impl.client;

//import org.glassfish.external.probe.provider.annotations.ProbeListener;

import java.lang.annotation.*;

/* BOOBY TRAP SITTING RIGHT HERE!!!  There is a ProbeListener in org.glassfish.flashlight.client
 * -- don't use that one or everything will fail!
 * Do not use this import --> import org.glassfish.flashlight.client.* --> import individually instead!!
 */

import org.glassfish.external.probe.provider.annotations.ProbeListener;
import org.glassfish.flashlight.client.ProbeClientInvoker;
import org.glassfish.flashlight.client.ProbeClientInvokerFactory;
import org.glassfish.flashlight.client.ProbeClientMediator;
import org.glassfish.flashlight.client.ProbeClientMethodHandle;
import org.glassfish.flashlight.provider.FlashlightProbe;
import org.glassfish.flashlight.provider.ProbeRegistry;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.PostConstruct;
import com.sun.logging.LogDomains;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import java.io.PrintWriter;

/**
 * @author Mahesh Kannan
 *         Date: Jan 27, 2008
 * @author Byron Nevins, significant rewrite/refactor August 2009
 */
@Service
public class FlashlightProbeClientMediator
        implements ProbeClientMediator, PostConstruct {

    private static final ProbeRegistry probeRegistry = ProbeRegistry.getInstance();
    private static boolean btraceAgentAttached = false;

    private static final Logger logger =
        LogDomains.getLogger(FlashlightProbeClientMediator.class, LogDomains.MONITORING_LOGGER);

    private static final PrintWriter fpw = 
        new FlashLightBTracePrintWriter(new NullStream(), logger);

    private static ProbeClientMediator _me = new FlashlightProbeClientMediator();

    private static AtomicBoolean agentInitialized =
            new AtomicBoolean(false);

    private AtomicInteger clientIdGenerator =
            new AtomicInteger(0);

    private static ConcurrentHashMap<Integer, Object> clients =
            new ConcurrentHashMap<Integer, Object>();

    Instrumentation inst = null;

    public void postConstruct() {
        _me = this;
    }


    public static ProbeClientMediator getInstance() {
        return _me;
    }

    public static Object getClient(int id) {
        return clients.get(id);
    }

    //TODO this method is screwy!!
    public static boolean isAgentAttached() {
        if (agentInitialized.get()) {
            return btraceAgentAttached;
        }
        synchronized (agentInitialized) {
            if (agentInitialized.get()) {
                return btraceAgentAttached;
            }
            String btp = System.getProperty("btrace.port");
            if ((btp == null) || (btp.length() <= 0)) {
                btraceAgentAttached = false;
            } else {
                try {
                    Integer.parseInt(btp);
                    btraceAgentAttached = true;
                } catch (NumberFormatException nfe) {
                    btraceAgentAttached = false;
                }
            }
            agentInitialized.set(true);
        }
        return btraceAgentAttached;
    }

    /* The difference between the 2 overloaded registerListener() methods
     * is that for DTraceListeners you have to supply a FlashlightProbe.
     * For java Listeners the Listener object itself has the
     * probe name in an annotation...
     */

    public synchronized Collection<ProbeClientMethodHandle> registerListener(Object listener) {
        return registerListener(listener, null);
    }

    public Collection<ProbeClientMethodHandle> registerListener(Object listener, FlashlightProbe probe) {
        List<ProbeClientMethodHandle>   pcms                                = new ArrayList<ProbeClientMethodHandle>();
        List<FlashlightProbe>           probesRequiringClassTransformation  = new ArrayList<FlashlightProbe>();

        if(probe == null)
            registerJavaListener(listener, pcms, probesRequiringClassTransformation);
        else
            registerDTraceListener(listener, probe, pcms, probesRequiringClassTransformation);

        transformProbes(listener, probesRequiringClassTransformation);

        return pcms;
    }

    private void registerJavaListener(
            Object listener,
            List<ProbeClientMethodHandle> pcms,
            List<FlashlightProbe> probesRequiringClassTransformation) {

        List<MethodProbe> methodProbePairs = handleListenerAnnotations(listener.getClass());

        if(methodProbePairs.isEmpty())
            return;

        for(MethodProbe mp : methodProbePairs) {
            FlashlightProbe probe = mp.probe;
            ProbeClientInvoker invoker = ProbeClientInvokerFactory.createInvoker(listener, mp.method, probe);
            ProbeClientMethodHandleImpl hi = new ProbeClientMethodHandleImpl(invoker.getId(), invoker, probe);
            pcms.add(hi);

            if (probe.addInvoker(invoker))
                probesRequiringClassTransformation.add(probe);
        }
    }

    private void registerDTraceListener(
            Object listener,
            FlashlightProbe probe,
            List<ProbeClientMethodHandle> pcms,
            List<FlashlightProbe> probesRequiringClassTransformation) {
    }

    private void transformProbes(Object listener, List<FlashlightProbe> probes) {
        if(probes.isEmpty())
            return;

        int clientID = clientIdGenerator.incrementAndGet();
        clients.put(clientID, listener);

        byte [] bArr = BtraceClientGenerator.generateBtraceClientClassData(clientID, probes);

        if (bArr == null)
            throw new RuntimeException("Internal Error: BtraceClientGenerator.generateBtraceClientClassData() returned null");

        if(isAgentAttached())
            submit2BTrace(bArr);
    }

    /**
     * Pick out all methods in the listener with the correct annotation, look
     * up the referenced Probe and return a list of all such pairs.
     * @param listenerClass
     * @return 
     */
    private List<MethodProbe> handleListenerAnnotations(Class listenerClass) {
        List<MethodProbe> mp = new LinkedList<MethodProbe> ();

        for (Method method : listenerClass.getDeclaredMethods()) {

            Annotation[] anns = method.getDeclaredAnnotations();

            System.out.println(Arrays.toString(anns));

            ProbeListener probeAnn = method.getAnnotation(ProbeListener.class);

            if (probeAnn == null)
                continue;

            String probeString = probeAnn.value();
            FlashlightProbe probe = probeRegistry.getProbe(probeString);

            if (probe == null)
                throw new RuntimeException("Invalid probe desc: " + probeString);
            
            mp.add(new MethodProbe(method, probe));
        }
        
        return mp;
    }

    private void submit2BTrace(byte [] bArr) {
        try {
            ClassLoader scl = this.getClass().getClassLoader().getSystemClassLoader();
            Class agentMainClass = scl.loadClass("com.sun.btrace.agent.Main");
            Class[] params = new Class[] {(new byte[0]).getClass(), PrintWriter.class};
            Method mthd = agentMainClass.getMethod("handleFlashLightClient", params);
            mthd.invoke(null, new Object[] {bArr, fpw});
        } 
        catch(Exception e) {
            throw new RuntimeException("BTrace Error");
        }
    }


    // this is just used internally for cleanly organizing the code.
    private static class MethodProbe {
        MethodProbe(Method m, FlashlightProbe p) {
            method = m;
            probe = p;
        }
        Method          method;
        FlashlightProbe probe;
    }
}
