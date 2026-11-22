package com.testframe.plugin.config;

import com.testframe.plugin.Json;
import com.testframe.plugin.engine.HttpCaller;
import com.testframe.plugin.engine.MongoRunner;
import com.testframe.plugin.engine.RunEngine;
import com.testframe.plugin.project.ProjectRuns;
import com.testframe.plugin.project.ProjectsRoot;
import io.github.jsonassert.JsonataAssertionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;

/** The plugin's beans: plain classes (the CLI builds the same graph without Spring). */
@Configuration
public class PluginConfig {

    @Bean
    public JsonMapper jsonMapper() {
        return Json.MAPPER;
    }

    @Bean(destroyMethod = "close")
    public MongoRunner mongoRunner() {
        return new MongoRunner();
    }

    @Bean
    public HttpCaller httpCaller() {
        return new HttpCaller();
    }

    @Bean
    public JsonataAssertionService jsonataAssertionService() {
        return new JsonataAssertionService();
    }

    @Bean
    public RunEngine runEngine(MongoRunner mongo, HttpCaller http, JsonataAssertionService assertions) {
        return new RunEngine(mongo, http, assertions, Json.MAPPER);
    }

    @Bean
    public ProjectRuns projectRuns(RunEngine engine) {
        return new ProjectRuns(engine, ProjectRuns.httpFetcher(), Json.MAPPER);
    }

    /** {@code testframe.projects-dir} (env {@code TESTFRAME_PROJECTS_DIR}): the projects the tour API serves. */
    @Bean
    public ProjectsRoot projectsRoot(@Value("${testframe.projects-dir:}") String projectsDir) {
        return new ProjectsRoot(projectsDir == null || projectsDir.isBlank() ? null : Path.of(projectsDir));
    }
}
