package com.testframe.plugin.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The tour screens are the static build under {@code static/tour/}. Spring Boot only maps the
 * welcome page of the static root, so {@code /tour} and {@code /tour/} (the documented entry,
 * {@code /tour?project={name}}) would be 404 without an explicit forward to the build's entry page.
 */
@Configuration
public class TourRoutes implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/tour").setViewName("forward:/tour/index.html");
        registry.addViewController("/tour/").setViewName("forward:/tour/index.html");
    }
}
