#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <time.h>

#include "eccodes.h"

static int report_codes_error(const char* path, const char* operation, int error)
{
    fprintf(stderr, "%s: %s failed: %s\n", path, operation,
            codes_get_error_message(error));
    return 1;
}

static int decode_file(const char* path)
{
    FILE* input = fopen(path, "rb");
    if (input == NULL) {
        fprintf(stderr, "%s: fopen failed: %s\n", path, strerror(errno));
        return 1;
    }

    const clock_t started = clock();
    size_t message_count = 0;
    size_t total_values = 0;
    double file_min = 0.0;
    double file_max = 0.0;
    int have_finite_value = 0;
    int result = 0;

    for (;;) {
        int error = CODES_SUCCESS;
        codes_handle* handle =
            codes_handle_new_from_file(NULL, input, PRODUCT_GRIB, &error);
        if (handle == NULL) {
            if (error != CODES_SUCCESS) {
                result = report_codes_error(path, "codes_handle_new_from_file", error);
            }
            break;
        }
        if (error != CODES_SUCCESS) {
            result = report_codes_error(path, "codes_handle_new_from_file", error);
            codes_handle_delete(handle);
            break;
        }

        long grid_template = -1;
        long product_template = -1;
        long data_template = -1;
        error = codes_get_long(handle, "gridDefinitionTemplateNumber", &grid_template);
        if (error != CODES_SUCCESS) {
            result = report_codes_error(path, "gridDefinitionTemplateNumber", error);
            codes_handle_delete(handle);
            break;
        }
        error = codes_get_long(handle, "productDefinitionTemplateNumber", &product_template);
        if (error != CODES_SUCCESS) {
            result = report_codes_error(path, "productDefinitionTemplateNumber", error);
            codes_handle_delete(handle);
            break;
        }
        error = codes_get_long(handle, "dataRepresentationTemplateNumber", &data_template);
        if (error != CODES_SUCCESS) {
            result = report_codes_error(path, "dataRepresentationTemplateNumber", error);
            codes_handle_delete(handle);
            break;
        }

        size_t value_count = 0;
        error = codes_get_size(handle, "values", &value_count);
        if (error != CODES_SUCCESS) {
            result = report_codes_error(path, "codes_get_size(values)", error);
            codes_handle_delete(handle);
            break;
        }
        if (value_count == 0 || value_count > SIZE_MAX / sizeof(double)) {
            fprintf(stderr, "%s: invalid values length: %zu\n", path, value_count);
            result = 1;
            codes_handle_delete(handle);
            break;
        }

        double* values = malloc(value_count * sizeof(*values));
        if (values == NULL) {
            fprintf(stderr, "%s: allocation failed for %zu values\n", path, value_count);
            result = 1;
            codes_handle_delete(handle);
            break;
        }

        size_t decoded_count = value_count;
        error = codes_get_double_array(handle, "values", values, &decoded_count);
        if (error != CODES_SUCCESS) {
            free(values);
            result = report_codes_error(path, "codes_get_double_array(values)", error);
            codes_handle_delete(handle);
            break;
        }
        if (decoded_count != value_count) {
            fprintf(stderr, "%s: decoded values length drifted: expected=%zu actual=%zu\n",
                    path, value_count, decoded_count);
            free(values);
            result = 1;
            codes_handle_delete(handle);
            break;
        }

        double message_min = 0.0;
        double message_max = 0.0;
        int have_message_finite = 0;
        for (size_t index = 0; index < decoded_count; ++index) {
            const double value = values[index];
            if (!isfinite(value)) {
                continue;
            }
            if (!have_message_finite) {
                message_min = value;
                message_max = value;
                have_message_finite = 1;
            } else {
                if (value < message_min) message_min = value;
                if (value > message_max) message_max = value;
            }
        }
        free(values);

        if (!have_message_finite) {
            fprintf(stderr, "%s: message %zu decoded no finite values\n", path,
                    message_count + 1);
            result = 1;
            codes_handle_delete(handle);
            break;
        }

        if (!have_finite_value) {
            file_min = message_min;
            file_max = message_max;
            have_finite_value = 1;
        } else {
            if (message_min < file_min) file_min = message_min;
            if (message_max > file_max) file_max = message_max;
        }

        ++message_count;
        total_values += decoded_count;
        printf("message file=%s index=%zu gdt=%ld pdt=%ld drt=%ld values=%zu min=%.17g max=%.17g\n",
               path, message_count, grid_template, product_template, data_template,
               decoded_count, message_min, message_max);
        codes_handle_delete(handle);
    }

    if (fclose(input) != 0 && result == 0) {
        fprintf(stderr, "%s: fclose failed: %s\n", path, strerror(errno));
        result = 1;
    }
    if (result != 0) {
        return result;
    }
    if (message_count == 0 || !have_finite_value) {
        fprintf(stderr, "%s: decoded zero usable GRIB messages\n", path);
        return 1;
    }

    const clock_t finished = clock();
    struct rusage usage;
    memset(&usage, 0, sizeof(usage));
    if (getrusage(RUSAGE_SELF, &usage) != 0) {
        fprintf(stderr, "%s: getrusage failed: %s\n", path, strerror(errno));
        return 1;
    }
    const double cpu_seconds =
        (double)(finished - started) / (double)CLOCKS_PER_SEC;

    printf("summary file=%s messages=%zu values=%zu min=%.17g max=%.17g cpu_seconds=%.6f max_rss_kib_linux=%ld\n",
           path, message_count, total_values, file_min, file_max, cpu_seconds,
           usage.ru_maxrss);
    return 0;
}

int main(int argc, char** argv)
{
    if (argc < 2) {
        fprintf(stderr, "usage: %s GRIB2 [GRIB2 ...]\n", argv[0]);
        return 2;
    }

    for (int index = 1; index < argc; ++index) {
        if (decode_file(argv[index]) != 0) {
            return 1;
        }
    }
    return 0;
}
