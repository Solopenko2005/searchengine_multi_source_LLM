package searchengine.controller;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class RoleNavigationTemplateTest {

    @Test
    void visitorNavigationContainsOnlyStatisticsSearchAndAssistant() throws Exception {
        Document template = Jsoup.parse(getClass().getClassLoader()
                .getResourceAsStream("templates/index.html"), StandardCharsets.UTF_8.name(), "");

        assertThat(template.select(".Tabs-links .Tabs-link.AdminOnly"))
                .extracting(element -> element.attr("href"))
                .containsExactlyInAnyOrder("#management", "#library");
        assertThat(template.select(".Tabs-links .Tabs-link:not(.AdminOnly)"))
                .extracting(element -> element.attr("href"))
                .containsExactlyInAnyOrder("#dashboard", "#search", "#assistant");
    }
}
