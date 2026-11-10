package com.example.books;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/books")
public class BookController {

    // Request body is a separate record so clients can't set the id (mass assignment).
    // @Size matches the VARCHAR(255) column: without it a long title is a 500 instead of a 400.
    public record BookRequest(
            @NotBlank @Size(max = 255) String title,
            @NotBlank @Size(max = 255) String author,
            @NotNull @Positive Integer publicationYear) {
    }

    private final BookRepository repository;

    public BookController(BookRepository repository) {
        this.repository = repository;
    }

    // ?page=0&size=20&sort=title,asc — sorted by id by default so pages are stable.
    @GetMapping
    public PagedModel<Book> findAll(@PageableDefault(sort = "id") Pageable pageable) {
        return new PagedModel<>(repository.findAll(pageable));
    }

    @GetMapping("/{id}")
    public Book findById(@PathVariable Long id) {
        return repository.findById(id).orElseThrow(() -> notFound(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Book create(@Valid @RequestBody BookRequest req) {
        return repository.save(new Book(req.title(), req.author(), req.publicationYear()));
    }

    @PutMapping("/{id}")
    @Transactional
    public Book update(@PathVariable Long id, @Valid @RequestBody BookRequest req) {
        Book book = findById(id);
        book.update(req.title(), req.author(), req.publicationYear());
        return book; // dirty checking flushes the change at commit
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        if (!repository.existsById(id)) throw notFound(id);
        repository.deleteById(id);
    }

    // ?sort=unknownField: Spring Data rejects it (no SQL injection possible) but would answer 500.
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail badSort(PropertyReferenceException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    private static ResponseStatusException notFound(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Book " + id + " not found");
    }
}
