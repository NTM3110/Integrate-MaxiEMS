package io.openems.edge.customerinfo;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(name = "Customer Info")
public @interface Config {

	@AttributeDefinition(name = "Component-ID")
	String id() default CustomerInfoImpl.COMPONENT_ID;

	@AttributeDefinition(name = "Alias")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Business name")
	String businessName() default "";

	@AttributeDefinition(name = "Business code")
	String businessCode() default "";

	@AttributeDefinition(name = "Address")
	String address() default "";

	@AttributeDefinition(name = "Installation location")
	String installLocation() default "";
}
