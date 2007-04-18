/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.ipojo.composite.service.provides;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.felix.ipojo.composite.service.provides.manipulation.Manipulator;
import org.apache.felix.ipojo.composite.service.provides.manipulation.POJOWriter;
import org.apache.felix.ipojo.metadata.Attribute;
import org.apache.felix.ipojo.metadata.Element;
import org.apache.felix.ipojo.util.Logger;
import org.osgi.framework.BundleContext;

/**
 * Check and build a compostion, i.e. a pojo containing the composition.
 * @author <a href="mailto:felix-dev@incubator.apache.org">Felix Project Team</a>
 */
public class CompositionMetadata {

    /**
     * Implemented composition.
     */
    private SpecificationMetadata m_specification;

    /**
     * Name of the composition.
     */
    private String m_name;

    /**
     * Bundle Context.
     */
    private BundleContext m_context;

    /**
     * Manipulated field of the build Pojo. 
     */
    private HashMap m_manipulatedFields = new HashMap();

    /**
     * Manipulated interface of the build Pojo.
     */
    private String[] m_manipulatedInterfaces = new String[0];

    /**
     * Reference on the handler.
     */
    private ProvidedServiceHandler m_handler;

    /**
     * List of Mappings.
     */
    private List m_mappings = new ArrayList();

    /**
     * Constructor.
     * @param bc : bundle context
     * @param description : 'provides' element
     * @param psh : parent handler 
     * @param name : name of the composition.
     */
    public CompositionMetadata(BundleContext bc, Element description, ProvidedServiceHandler psh, String name) {
        m_context = bc;
        m_handler = psh;
        // Get the composition name
        m_name = description.getAttribute("specification") + name;

        // Get implemented service specification
        String spec = description.getAttribute("specification");
        m_specification = new SpecificationMetadata(spec, m_context, false, false, m_handler);

        Element[] mappings = description.getElements("delegation");
        for (int i = 0; i < mappings.length; i++) {
            String methodName = mappings[i].getAttribute("method");
            MethodMetadata method = m_specification.getMethodByName(methodName);
            if (method == null) {
                m_handler.getManager().getFactory().getLogger().log(Logger.ERROR, "The method " + methodName + " does not exist in the specicifation " + spec);
                return;
            }

            if (mappings[i].getAttribute("policy").equalsIgnoreCase("All")) {
                method.setAllPolicy();
            }
        }
    }

    protected BundleContext getBundleContext() {
        return m_context;
    }

    public String getName() {
        return m_name;
    }

    public SpecificationMetadata getSpecificationMetadata() {
        return m_specification;
    }

    /**
     * Build Available Mappings.
     */
    private void buildAvailableMappingList() {
        int index = 0;

        for (int i = 0; i < m_handler.getSpecifications().size(); i++) {
            SpecificationMetadata spec = (SpecificationMetadata) m_handler.getSpecifications().get(i);
            FieldMetadata field = new FieldMetadata(spec);
            field.setName("_field" + index);
            if (spec.isOptional()) {
                field.setOptional(true);
            }
            if (spec.isAggregate()) {
                field.setAggregate(true);
            }
            Mapping map = new Mapping(spec, field);
            m_mappings.add(map);
            index++;
        }
    }

    /**
     * Build the delegation mapping.
     * @throws CompositionException : occurs when the mapping cannot be infers correctly
     */
    protected void buildMapping() throws CompositionException {
        buildAvailableMappingList();

        // Dependency closure is OK, now look for method delegation
        Map/* <MethodMetadata, Mapping> */availableMethods = new HashMap();

        for (int i = 0; i < m_mappings.size(); i++) {
            Mapping map = (Mapping) m_mappings.get(i);
            SpecificationMetadata spec = map.getSpecification();
            for (int j = 0; j < spec.getMethods().size(); j++) {
                MethodMetadata method = (MethodMetadata) spec.getMethods().get(j);
                availableMethods.put(method, map);
            }
        }

        // For each needed method search if available and store the mapping
        for (int j = 0; j < m_specification.getMethods().size(); j++) {
            MethodMetadata method = (MethodMetadata) m_specification.getMethods().get(j);
            Set keys = availableMethods.keySet();
            Iterator it = keys.iterator();
            boolean found = false;
            while (it.hasNext() & !found) {
                MethodMetadata met = (MethodMetadata) it.next();
                if (met.equals(method)) {
                    found = true;
                    FieldMetadata field = ((Mapping) availableMethods.get(met)).getField();
                    field.setUseful(true);
                    method.setDelegation(field);
                    // Test optionality
                    if (field.isOptional() && !method.getExceptions().contains("java/lang/UnsupportedOperationException")) {
                        m_handler.getManager().getFactory().getLogger().log(Logger.WARNING, "The method " + method.getMethodName() + " could not be provided correctly : the specification " + field.getSpecification().getName() + " is optional");
                    }
                }
            }
            if (!found) {
                throw new CompositionException("Composition non consistent : " + method.getMethodName() + " could not be delegated");
            }
        }
    }

    /**
     * Build a service implementation.
     * @return the byte[] of the POJO.
     */
    protected byte[] buildPOJO() {
        String resource = m_specification.getName().replace('.', '/') + ".class";
        URL url = getBundleContext().getBundle().getResource(resource);
        byte[] pojo = POJOWriter.dump(url, m_specification.getName(), m_name, getFieldList(), getMethodList());
        Manipulator m = new Manipulator();
        try {
            byte[] ff = m.process(pojo);
            m_manipulatedFields = m.getFields();
            m_manipulatedInterfaces = m.getInterfaces();
            return ff;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Build service implementation metadata.
     * @return Component Type metadata. 
     */
    protected Element buildMetadata() {
        Element elem = new Element("component", "");
        Attribute className = new Attribute("className", m_name);
        Attribute factory = new Attribute("factory", "no");
        elem.addAttribute(className);
        elem.addAttribute(factory);

        // Provides
        Element provides = new Element("provides", "");
        provides.addAttribute(new Attribute("specification", m_specification.getName()));
        elem.addElement(provides);

        // Dependencies
        List fields = getFieldList();
        for (int i = 0; i < fields.size(); i++) {
            FieldMetadata field = (FieldMetadata) fields.get(i);
            if (field.isUseful()) {
                Element dep = new Element("Dependency", "");
                dep.addAttribute(new Attribute("field", field.getName()));
                if (field.getSpecification().isOptional()) {
                    dep.addAttribute(new Attribute("optional", "true"));
                }
                elem.addElement(dep);
            }
        }

        // Insert information to metadata
        Element manip = new Element("Manipulation", "");
        for (int j = 0; j < m_manipulatedInterfaces.length; j++) {
            // Create an interface element for each implemented interface
            Element itf = new Element("Interface", "");
            Attribute att = new Attribute("name", m_manipulatedInterfaces[j]);
            itf.addAttribute(att);
            manip.addElement(itf);
        }

        Iterator it = m_manipulatedFields.keySet().iterator();
        while (it.hasNext()) {
            Element field = new Element("Field", "");
            String name = (String) it.next();
            String type = (String) m_manipulatedFields.get(name);
            Attribute attName = new Attribute("name", name);
            Attribute attType = new Attribute("type", type);
            field.addAttribute(attName);
            field.addAttribute(attType);
            manip.addElement(field);
        }

        elem.addElement(manip);

        return elem;
    }

    /**
     * Get the field list to use for the delegation.
     * @return the field list.
     */
    private List getFieldList() {
        List list = new ArrayList();
        for (int i = 0; i < m_mappings.size(); i++) {
            Mapping map = (Mapping) m_mappings.get(i);
            list.add(map.getField());
        }
        return list;
    }

    /**
     * Get the method lsit contained in the implemented specification.
     * @return the List of implemented method.
     */
    private List getMethodList() {
        return m_specification.getMethods();
    }
    
    /**
     * Store links between Field and pointed Specification.
     */
    private class Mapping {

        /**
         * Specification.
         */
        private SpecificationMetadata m_spec;

        /**
         * Field.
         */
        private FieldMetadata m_field;

        /**
         * Constructor.
         * @param spec : specification metadata.
         * @param field : the field.
         */
        public Mapping(SpecificationMetadata spec, FieldMetadata field) {
            m_spec = spec;
            m_field = field;
        }

        public SpecificationMetadata getSpecification() {
            return m_spec;
        }

        public FieldMetadata getField() {
            return m_field;
        }

    }

}
