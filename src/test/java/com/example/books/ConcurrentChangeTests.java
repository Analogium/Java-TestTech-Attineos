package com.example.books;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Separate class: the spy below replaces the repository for the whole Spring context.
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentChangeTests {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @PersistenceContext
    EntityManager em;
    @MockitoSpyBean
    BookRepository repository;

    @Test
    void bookDeletedWhileBeingUpdatedIs409Not500() throws Exception {
        assertPutIs409WhenAnotherRequestRunsRightAfterOurRead("DELETE FROM book WHERE id = ?");
    }

    @Test
    void bookUpdatedWhileBeingUpdatedIs409() throws Exception {
        // Both PUTs sent version 0 and passed the controller's check: only @Version catches the second one.
        assertPutIs409WhenAnotherRequestRunsRightAfterOurRead("UPDATE book SET title = 'Other', version = version + 1 WHERE id = ?");
    }

    private void assertPutIs409WhenAnotherRequestRunsRightAfterOurRead(String otherRequestSql) throws Exception {
        long id = repository.save(new Book("T", "A", 2000)).getId();
        // Simulates another request writing the row right after the PUT has read it.
        doAnswer(call -> {
            Book found = em.find(Book.class, id); // what the real findById does
            jdbc.update(otherRequestSql, id);
            return Optional.ofNullable(found);
        }).when(repository).findById(id);

        mvc.perform(put("/books/" + id).contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"T2","author":"A","publicationYear":2000,"version":0}"""))
                .andExpect(status().isConflict());
    }
}
