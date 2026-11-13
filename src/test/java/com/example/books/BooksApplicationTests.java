package com.example.books;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Year;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BooksApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void crudFlow() throws Exception {
        String body = """
                {"title":"Dune","author":"Frank Herbert","publicationYear":1965}""";

        String created = mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        mvc.perform(get("/books/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Dune"));
        mvc.perform(get("/books?size=100")).andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id", hasItem(id)));

        mvc.perform(put("/books/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("1965", "1966,\"version\":0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicationYear").value(1966))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/books/" + id)).andExpect(jsonPath("$.publicationYear").value(1966));

        mvc.perform(delete("/books/" + id)).andExpect(status().isNoContent());
        mvc.perform(get("/books/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/books/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void paginatesSortsAndCapsPageSize() throws Exception {
        for (String title : new String[]{"Zola", "Asimov", "Camus"}) {
            mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                    {"title":"%s","author":"X","publicationYear":2000}""".formatted(title)));
        }
        mvc.perform(get("/books?size=2&sort=title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Asimov"))
                .andExpect(jsonPath("$.page.size").value(2));
        mvc.perform(get("/books?size=5000")).andExpect(jsonPath("$.page.size").value(100));
        mvc.perform(get("/books?sort=foo")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidBook() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"","author":"X","publicationYear":-1}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsTooLongTitleWith400Not500() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"%s","author":"X","publicationYear":2000}""".formatted("a".repeat(256))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trimsTextAndRejectsBlankOrControlCharacters() throws Exception {
        // JSON escapes: \\u00a0 = no-break space, \\u0000 = NUL
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"  Dune\\u00a0","author":" Frank Herbert ","publicationYear":1965}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Dune"))
                .andExpect(jsonPath("$.author").value("Frank Herbert"));
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"\\u00a0 ","author":"A","publicationYear":1965}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("title: must not be blank")));
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"a\\u0000b","author":"A","publicationYear":1965}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("title: must not contain control characters, < or >")));
    }

    @Test
    void rejectsFutureYearAndLossyOrAmbiguousJson() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"T","author":"A","publicationYear":%d}""".formatted(Year.now().getValue() + 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("publicationYearNotInFuture: must not be in the future")));
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"T","author":"A","publicationYear":1965.9}"""))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"T","title":"U","author":"A","publicationYear":1965}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsSortOutsideWhitelistAndOverflowingPage() throws Exception {
        mvc.perform(get("/books").param("sort", "title;DROP TABLE book"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/books").param("sort", "title.length"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/books?page=99999999&size=100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lostUpdateIsRejected() throws Exception {
        String created = mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Shared","author":"A","publicationYear":2000}"""))
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");
        String edit = """
                {"title":"%s","author":"A","publicationYear":2000,"version":0}""";

        // Bob and Alice both read version 0. Bob saves first.
        mvc.perform(put("/books/" + id).contentType(MediaType.APPLICATION_JSON).content(edit.formatted("Bob")))
                .andExpect(status().isOk());
        // Alice still sends version 0: rejected, Bob's change is kept.
        mvc.perform(put("/books/" + id).contentType(MediaType.APPLICATION_JSON).content(edit.formatted("Alice")))
                .andExpect(status().isConflict());
        mvc.perform(get("/books/" + id)).andExpect(jsonPath("$.title").value("Bob"));
        // No version at all: the client must say what it read.
        mvc.perform(put("/books/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"X","author":"A","publicationYear":2000}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blocksXss() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"<script>alert(1)</script>","author":"A","publicationYear":2000}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem("title: must not contain control characters, < or >")));
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"Pride & Prejudice","author":"Jane O'Neil \\"J\\"","publicationYear":1813}"""))
                .andExpect(status().isCreated());
        mvc.perform(get("/books"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"));
    }

    @Test
    void ignoresClientSuppliedId() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"id":999,"title":"T","author":"A","publicationYear":2000}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(999)));
    }
}
