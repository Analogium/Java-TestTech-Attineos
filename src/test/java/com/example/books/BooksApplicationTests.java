package com.example.books;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
                .andReturn().getResponse().getContentAsString();
        int id = JsonPath.read(created, "$.id");

        mvc.perform(get("/books/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("Dune"));
        mvc.perform(get("/books?size=100")).andExpect(status().isOk()).andExpect(jsonPath("$.content[*].id", hasItem(id)));

        mvc.perform(put("/books/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("1965", "1966")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicationYear").value(1966));
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
    void ignoresClientSuppliedId() throws Exception {
        mvc.perform(post("/books").contentType(MediaType.APPLICATION_JSON).content("""
                        {"id":999,"title":"T","author":"A","publicationYear":2000}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", not(999)));
    }
}
