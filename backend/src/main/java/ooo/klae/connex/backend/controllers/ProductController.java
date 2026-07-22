package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.dto.ProductDto;
import ooo.klae.connex.backend.services.ProductService;

/** REST controller for the workspace-scoped product/service catalog. */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService productService;

    @GetMapping
    public List<ProductDto> getAll() {
        return productService.getAll().stream().map(ProductDto::from).toList();
    }

    @GetMapping("/{id}")
    public ProductDto getById(@PathVariable int id) {
        return ProductDto.from(productService.getById(id));
    }

    @PostMapping
    public ProductDto create(@Valid @RequestBody ProductDto dto) {
        return ProductDto.from(productService.create(dto.toBean()));
    }

    @PutMapping("/{id}")
    public ProductDto update(@PathVariable int id, @Valid @RequestBody ProductDto dto) {
        return ProductDto.from(productService.update(id, dto.toBean()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable int id) {
        productService.delete(id);
    }
}
