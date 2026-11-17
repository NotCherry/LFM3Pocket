use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong, jobjectArray};
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
            let _ = PHONEMIZER.set(phonemizer);
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
    let ids = env.new_long_array(phones.ids.len() as i32).unwrap();
    let stress = env.new_long_array(phones.stress.len() as i32).unwrap();
    let id_values: Vec<jlong> = phones.ids.iter().map(|&v| v as jlong).collect();
    let stress_values: Vec<jlong> = phones.stress.iter().map(|&v| v as jlong).collect();
    env.set_long_array_region(&ids, 0, &id_values).unwrap();
    env.set_long_array_region(&stress, 0, &stress_values).unwrap();
    let long_array_class = env.find_class("[J").unwrap();
    let outer = env.new_object_array(2, long_array_class, jni::objects::JObject::null()).unwrap();
    env.set_object_array_element(&outer, 0, ids).unwrap();
    env.set_object_array_element(&outer, 1, stress).unwrap();
    outer.into_raw()
}