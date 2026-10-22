package id.ppob2.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring serves static files but not a directory's index page; map the UI's entry points explicitly. */
@Configuration
public class BackofficeUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/backoffice", "/backoffice/");
        registry.addViewController("/backoffice/").setViewName("forward:/backoffice/index.html");
    }
}
