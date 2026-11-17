use jni::objects::{JClass, JObject, JString};
use jni::sys::{jboolean, jobjectArray};
use jni::JNIEnv;
use std::path::Path;
use std::sync::OnceLock;

static PHONEMIZER: OnceLock<dizione::Phonemizer> = OnceLock::new();

#[no_mangle]
pub extern "system" fn Java_com_notcherry_lfm3pocket_TinyVocaPhonemizerBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    data_dir: JString,
) -> jboolean {
    let dir: String = match env.get_string(&data_dir) {
        Ok(value) => value.into(),
        Err(_) => return 0,
    };
    match dizione::Phonemizer::load(Path::new(&dir)) {
        Ok(phonemizer) => {
            if PHONEMIZER.set(phonemizer).is_err() {
                return 1;
            }
            1
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_notcherry_lfm3pocket_TinyVocaPhonemizerBridge_nativePhonemize(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jobjectArray {
    let Some(phonemizer) = PHONEMIZER.get() else {
        return std::ptr::null_mut();
    };
    let utterance: String = match env.get_string(&text) {
        Ok(value) => value.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let phones = match phonemizer.phonemize(&utterance) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let ids = match env.new_long_array(phones.ids.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let stress = match env.new_long_array(phones.stress.len() as i32) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let id_values: Vec<i64> = phones.ids.iter().map(|&v| v as i64).collect();
    let stress_values: Vec<i64> = phones.stress.iter().map(|&v| v as i64).collect();
    if env.set_long_array_region(&ids, 0, &id_values).is_err() {
        return std::ptr::null_mut();
    }
    if env.set_long_array_region(&stress, 0, &stress_values).is_err() {
        return std::ptr::null_mut();
    }
    let long_array_class = match env.find_class("[J") {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let outer = match env.new_object_array(2, long_array_class, JObject::null()) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    if env.set_object_array_element(&outer, 0, ids).is_err() {
        return std::ptr::null_mut();
    }
    if env.set_object_array_element(&outer, 1, stress).is_err() {
        return std::ptr::null_mut();
    }
    outer.into_raw()
}