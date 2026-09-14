#include <jni.h>

#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "eccodes.h"

#define METEOONE_MAX_PAYLOAD_BYTES (64U * 1024U * 1024U)
#define METEOONE_METADATA_LONG_COUNT 35
#define METEOONE_GEOMETRY_DOUBLE_COUNT 4
#define METEOONE_MISSING_LONG INT64_MIN

static const char* const ILLEGAL_ARGUMENT = "java/lang/IllegalArgumentException";
static const char* const ILLEGAL_STATE = "java/lang/IllegalStateException";
static const char* const NATIVE_MESSAGE_CLASS =
    "com/sl/meteoone/forecast/data/grib/NativeGribMessage";
static const char* const NATIVE_MESSAGE_CTOR = "([J[D[D)V";

static void throw_java(JNIEnv* env, const char* class_name, const char* message)
{
    jclass exception_class = (*env)->FindClass(env, class_name);
    if (exception_class == NULL) {
        return;
    }
    (*env)->ThrowNew(env, exception_class, message);
    (*env)->DeleteLocalRef(env, exception_class);
}

static void throw_codes_error(JNIEnv* env, const char* operation, int error)
{
    char message[512];
    const char* detail = codes_get_error_message(error);
    if (detail == NULL) {
        detail = "unknown ecCodes error";
    }
    (void)snprintf(message, sizeof(message), "%s failed: %s", operation, detail);
    throw_java(env, ILLEGAL_STATE, message);
}

static uint64_t read_be_u64(const unsigned char* bytes)
{
    uint64_t value = 0;
    for (int index = 0; index < 8; ++index) {
        value = (value << 8U) | (uint64_t)bytes[index];
    }
    return value;
}

static int validate_and_count_messages(
    JNIEnv* env,
    const unsigned char* bytes,
    size_t payload_size,
    jint max_messages,
    jint* message_count)
{
    size_t offset = 0;
    jint count = 0;

    while (offset < payload_size) {
        if (payload_size - offset < 16U) {
            throw_java(env, ILLEGAL_ARGUMENT, "GRIB payload ends before a complete section 0");
            return 0;
        }
        const unsigned char* message = bytes + offset;
        if (memcmp(message, "GRIB", 4U) != 0 || message[7] != 2U) {
            throw_java(env, ILLEGAL_ARGUMENT, "GRIB payload contains a non-GRIB2 message");
            return 0;
        }

        const uint64_t encoded_length = read_be_u64(message + 8U);
        if (encoded_length < 20U || encoded_length > (uint64_t)(payload_size - offset)) {
            throw_java(env, ILLEGAL_ARGUMENT, "GRIB2 message length is outside the bounded payload");
            return 0;
        }
        if (encoded_length > (uint64_t)SIZE_MAX) {
            throw_java(env, ILLEGAL_ARGUMENT, "GRIB2 message length exceeds the platform size limit");
            return 0;
        }

        const size_t message_size = (size_t)encoded_length;
        if (memcmp(message + message_size - 4U, "7777", 4U) != 0) {
            throw_java(env, ILLEGAL_ARGUMENT, "GRIB2 message is missing its end marker");
            return 0;
        }

        ++count;
        if (count > max_messages) {
            throw_java(env, ILLEGAL_ARGUMENT, "GRIB payload contains too many messages");
            return 0;
        }
        offset += message_size;
    }

    if (offset != payload_size || count == 0) {
        throw_java(env, ILLEGAL_ARGUMENT, "GRIB payload framing is invalid");
        return 0;
    }
    *message_count = count;
    return 1;
}

static int get_required_long(
    JNIEnv* env,
    codes_handle* handle,
    const char* key,
    jlong* destination)
{
    long value = 0;
    const int error = codes_get_long(handle, key, &value);
    if (error != CODES_SUCCESS) {
        throw_codes_error(env, key, error);
        return 0;
    }
    *destination = (jlong)value;
    return 1;
}

static jlong get_optional_long(codes_handle* handle, const char* key)
{
    long value = 0;
    if (codes_get_long(handle, key, &value) != CODES_SUCCESS) {
        return (jlong)METEOONE_MISSING_LONG;
    }
    return (jlong)value;
}

static jdouble get_optional_double(codes_handle* handle, const char* key)
{
    double value = NAN;
    if (codes_get_double(handle, key, &value) != CODES_SUCCESS || !isfinite(value)) {
        return NAN;
    }
    return (jdouble)value;
}

static int fill_metadata(JNIEnv* env, codes_handle* handle, jlong* metadata)
{
    for (int index = 0; index < METEOONE_METADATA_LONG_COUNT; ++index) {
        metadata[index] = (jlong)METEOONE_MISSING_LONG;
    }

    const char* const required_keys[] = {
        "edition",
        "discipline",
        "parameterCategory",
        "parameterNumber",
        "productDefinitionTemplateNumber",
        "gridDefinitionTemplateNumber",
        "dataRepresentationTemplateNumber",
        "year",
        "month",
        "day",
        "hour",
        "minute",
        "second",
        "indicatorOfUnitOfTimeRange",
        "forecastTime",
        "typeOfFirstFixedSurface",
        "scaleFactorOfFirstFixedSurface",
        "scaledValueOfFirstFixedSurface",
    };

    for (int index = 0; index < 18; ++index) {
        if (!get_required_long(env, handle, required_keys[index], &metadata[index])) {
            return 0;
        }
    }

    metadata[18] = get_optional_long(handle, "yearOfEndOfOverallTimeInterval");
    metadata[19] = get_optional_long(handle, "monthOfEndOfOverallTimeInterval");
    metadata[20] = get_optional_long(handle, "dayOfEndOfOverallTimeInterval");
    metadata[21] = get_optional_long(handle, "hourOfEndOfOverallTimeInterval");
    metadata[22] = get_optional_long(handle, "minuteOfEndOfOverallTimeInterval");
    metadata[23] = get_optional_long(handle, "secondOfEndOfOverallTimeInterval");
    metadata[24] = get_optional_long(handle, "numberOfTimeRanges");
    metadata[25] = get_optional_long(handle, "typeOfStatisticalProcessing");
    metadata[26] = get_optional_long(handle, "indicatorOfUnitForTimeRange");
    metadata[27] = get_optional_long(handle, "lengthOfTimeRange");
    metadata[29] = get_optional_long(handle, "Ni");
    metadata[30] = get_optional_long(handle, "Nj");
    metadata[31] = get_optional_long(handle, "iScansNegatively");
    metadata[32] = get_optional_long(handle, "jScansPositively");
    metadata[33] = get_optional_long(handle, "jPointsAreConsecutive");
    metadata[34] = get_optional_long(handle, "alternativeRowScanning");
    return 1;
}

static void fill_geometry(codes_handle* handle, jdouble* geometry)
{
    geometry[0] = get_optional_double(handle, "latitudeOfFirstGridPointInDegrees");
    geometry[1] = get_optional_double(handle, "longitudeOfFirstGridPointInDegrees");
    geometry[2] = get_optional_double(handle, "iDirectionIncrementInDegrees");
    geometry[3] = get_optional_double(handle, "jDirectionIncrementInDegrees");
}

static jdoubleArray decode_values(
    JNIEnv* env,
    codes_handle* handle,
    jint max_total_values,
    size_t* decoded_total,
    jlong* metadata)
{
    size_t value_count = 0;
    int error = codes_get_size(handle, "values", &value_count);
    if (error != CODES_SUCCESS) {
        throw_codes_error(env, "codes_get_size(values)", error);
        return NULL;
    }
    if (value_count == 0U || value_count > (size_t)max_total_values) {
        throw_java(env, ILLEGAL_ARGUMENT, "Decoded GRIB value count is outside the bounded limit");
        return NULL;
    }
    if (*decoded_total > (size_t)max_total_values - value_count) {
        throw_java(env, ILLEGAL_ARGUMENT, "Decoded GRIB payload exceeds the total value limit");
        return NULL;
    }
    if (value_count > (size_t)INT_MAX) {
        throw_java(env, ILLEGAL_ARGUMENT, "Decoded GRIB value count exceeds the JNI array limit");
        return NULL;
    }

    jdoubleArray values = (*env)->NewDoubleArray(env, (jsize)value_count);
    if (values == NULL) {
        return NULL;
    }
    jdouble* elements = (*env)->GetDoubleArrayElements(env, values, NULL);
    if (elements == NULL) {
        (*env)->DeleteLocalRef(env, values);
        return NULL;
    }

    size_t actual_count = value_count;
    error = codes_get_double_array(handle, "values", elements, &actual_count);
    if (error != CODES_SUCCESS || actual_count != value_count) {
        (*env)->ReleaseDoubleArrayElements(env, values, elements, JNI_ABORT);
        (*env)->DeleteLocalRef(env, values);
        if (error != CODES_SUCCESS) {
            throw_codes_error(env, "codes_get_double_array(values)", error);
        } else {
            throw_java(env, ILLEGAL_STATE, "ecCodes returned an unexpected GRIB value count");
        }
        return NULL;
    }

    (*env)->ReleaseDoubleArrayElements(env, values, elements, 0);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, values);
        return NULL;
    }

    *decoded_total += value_count;
    metadata[28] = (jlong)value_count;
    return values;
}

JNIEXPORT void JNICALL
Java_com_sl_meteoone_forecast_data_grib_EcCodesNativeBridge_nativeConfigureDefinitions(
    JNIEnv* env,
    jobject receiver,
    jstring definitions_path)
{
    (void)receiver;
    if (definitions_path == NULL) {
        throw_java(env, ILLEGAL_ARGUMENT, "ecCodes definitions path must not be null");
        return;
    }

    const char* path = (*env)->GetStringUTFChars(env, definitions_path, NULL);
    if (path == NULL) {
        return;
    }
    if (path[0] == '\0') {
        (*env)->ReleaseStringUTFChars(env, definitions_path, path);
        throw_java(env, ILLEGAL_ARGUMENT, "ecCodes definitions path must not be empty");
        return;
    }

    codes_context_set_definitions_path(NULL, path);
    (*env)->ReleaseStringUTFChars(env, definitions_path, path);
}

JNIEXPORT jobjectArray JNICALL
Java_com_sl_meteoone_forecast_data_grib_EcCodesNativeBridge_nativeDecode(
    JNIEnv* env,
    jobject receiver,
    jbyteArray payload,
    jint max_messages,
    jint max_total_values)
{
    (void)receiver;
    if (payload == NULL) {
        throw_java(env, ILLEGAL_ARGUMENT, "GRIB payload must not be null");
        return NULL;
    }
    if (max_messages <= 0 || max_total_values <= 0) {
        throw_java(env, ILLEGAL_ARGUMENT, "GRIB decode limits must be positive");
        return NULL;
    }

    const jsize payload_length = (*env)->GetArrayLength(env, payload);
    if (payload_length <= 0 || (size_t)payload_length > METEOONE_MAX_PAYLOAD_BYTES) {
        throw_java(env, ILLEGAL_ARGUMENT, "GRIB payload exceeds the native bounded decode limit");
        return NULL;
    }

    jbyte* payload_bytes = (*env)->GetByteArrayElements(env, payload, NULL);
    if (payload_bytes == NULL) {
        return NULL;
    }

    jint message_count = 0;
    if (!validate_and_count_messages(
            env,
            (const unsigned char*)payload_bytes,
            (size_t)payload_length,
            max_messages,
            &message_count)) {
        (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
        return NULL;
    }

    jclass message_class = (*env)->FindClass(env, NATIVE_MESSAGE_CLASS);
    if (message_class == NULL) {
        (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
        return NULL;
    }
    jmethodID constructor = (*env)->GetMethodID(env, message_class, "<init>", NATIVE_MESSAGE_CTOR);
    if (constructor == NULL) {
        (*env)->DeleteLocalRef(env, message_class);
        (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
        return NULL;
    }

    jobjectArray result = (*env)->NewObjectArray(env, message_count, message_class, NULL);
    if (result == NULL) {
        (*env)->DeleteLocalRef(env, message_class);
        (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
        return NULL;
    }

    size_t decoded_total = 0U;
    size_t offset = 0U;
    for (jint message_index = 0; message_index < message_count; ++message_index) {
        const unsigned char* message = (const unsigned char*)payload_bytes + offset;
        const size_t message_size = (size_t)read_be_u64(message + 8U);
        codes_handle* handle = codes_handle_new_from_message_copy(NULL, message, message_size);
        if (handle == NULL) {
            throw_java(env, ILLEGAL_STATE, "ecCodes could not create a GRIB2 message handle");
            break;
        }

        jlong metadata[METEOONE_METADATA_LONG_COUNT];
        jdouble geometry[METEOONE_GEOMETRY_DOUBLE_COUNT];
        if (!fill_metadata(env, handle, metadata)) {
            codes_handle_delete(handle);
            break;
        }
        fill_geometry(handle, geometry);

        jdoubleArray values = decode_values(
            env,
            handle,
            max_total_values,
            &decoded_total,
            metadata);
        codes_handle_delete(handle);
        if (values == NULL) {
            break;
        }

        jlongArray metadata_array = (*env)->NewLongArray(env, METEOONE_METADATA_LONG_COUNT);
        jdoubleArray geometry_array = (*env)->NewDoubleArray(env, METEOONE_GEOMETRY_DOUBLE_COUNT);
        if (metadata_array == NULL || geometry_array == NULL) {
            if (metadata_array != NULL) (*env)->DeleteLocalRef(env, metadata_array);
            if (geometry_array != NULL) (*env)->DeleteLocalRef(env, geometry_array);
            (*env)->DeleteLocalRef(env, values);
            break;
        }
        (*env)->SetLongArrayRegion(
            env,
            metadata_array,
            0,
            METEOONE_METADATA_LONG_COUNT,
            metadata);
        (*env)->SetDoubleArrayRegion(
            env,
            geometry_array,
            0,
            METEOONE_GEOMETRY_DOUBLE_COUNT,
            geometry);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->DeleteLocalRef(env, metadata_array);
            (*env)->DeleteLocalRef(env, geometry_array);
            (*env)->DeleteLocalRef(env, values);
            break;
        }

        jobject native_message = (*env)->NewObject(
            env,
            message_class,
            constructor,
            metadata_array,
            geometry_array,
            values);
        (*env)->DeleteLocalRef(env, metadata_array);
        (*env)->DeleteLocalRef(env, geometry_array);
        (*env)->DeleteLocalRef(env, values);
        if (native_message == NULL) {
            break;
        }

        (*env)->SetObjectArrayElement(env, result, message_index, native_message);
        (*env)->DeleteLocalRef(env, native_message);
        if ((*env)->ExceptionCheck(env)) {
            break;
        }
        offset += message_size;
    }

    (*env)->DeleteLocalRef(env, message_class);
    (*env)->ReleaseByteArrayElements(env, payload, payload_bytes, JNI_ABORT);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->DeleteLocalRef(env, result);
        return NULL;
    }
    return result;
}
