#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "ggml.h"

#define LOG_TAG "BlazeJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static llama_model*   g_model   = nullptr;
static llama_context* g_context = nullptr;
static bool           g_loaded  = false;

extern "C" {

JNIEXPORT jint JNICALL
Java_com_blaze_agent_ai_BonsaiInference_loadModel(JNIEnv* env, jobject, jstring modelPath, jint nCtx, jint nThreads) {
    if (g_loaded) return 1;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading Bonsai model: %s", path);
    llama_backend_init();
    llama_model_params mParams = llama_model_default_params();
    mParams.n_gpu_layers = 0;
    g_model = llama_load_model_from_file(path, mParams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!g_model) { LOGE("Failed to load model"); return 0; }
    llama_context_params cParams = llama_context_default_params();
    cParams.n_ctx = (uint32_t)nCtx;
    cParams.n_threads = (uint32_t)nThreads;
    cParams.n_threads_batch = (uint32_t)nThreads;
    g_context = llama_new_context_with_model(g_model, cParams);
    if (!g_context) { llama_free_model(g_model); g_model = nullptr; return 0; }
    g_loaded = true;
    LOGI("Bonsai loaded (ctx=%d, threads=%d)", nCtx, nThreads);
    return 1;
}

JNIEXPORT jstring JNICALL
Java_com_blaze_agent_ai_BonsaiInference_complete(JNIEnv* env, jobject, jstring jPrompt, jint maxTokens) {
    if (!g_loaded || !g_model || !g_context) return env->NewStringUTF("[ERROR: Model not loaded]");
    const char* promptStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptStr);
    env->ReleaseStringUTFChars(jPrompt, promptStr);
    std::vector<llama_token> tokens(prompt.size() + 128);
    int nTokens = llama_tokenize(g_model, prompt.c_str(), (int32_t)prompt.size(), tokens.data(), (int32_t)tokens.size(), true, true);
    if (nTokens < 0) return env->NewStringUTF("[ERROR: Tokenization failed]");
    tokens.resize(nTokens);
    llama_batch batch = llama_batch_init(nTokens, 0, 1);
    for (int i = 0; i < nTokens; i++) {
        batch.token[i] = tokens[i]; batch.pos[i] = i;
        batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == nTokens - 1) ? 1 : 0;
    }
    batch.n_tokens = nTokens;
    if (llama_decode(g_context, batch) != 0) { llama_batch_free(batch); return env->NewStringUTF("[ERROR: Decode failed]"); }
    llama_batch_free(batch);
    std::string result;
    int n_cur = nTokens;
    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));
    while (n_cur < nTokens + maxTokens) {
        llama_token newToken = llama_sampler_sample(sampler, g_context, -1);
        if (llama_token_is_eog(g_model, newToken)) break;
        char buf[256];
        int n = llama_token_to_piece(g_model, newToken, buf, sizeof(buf), 0, true);
        if (n > 0) result.append(buf, n);
        llama_batch nb = llama_batch_init(1, 0, 1);
        nb.token[0] = newToken; nb.pos[0] = n_cur;
        nb.n_seq_id[0] = 1; nb.seq_id[0][0] = 0; nb.logits[0] = 1; nb.n_tokens = 1;
        llama_decode(g_context, nb); llama_batch_free(nb);
        n_cur++;
    }
    llama_sampler_free(sampler);
    llama_kv_cache_clear(g_context);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_blaze_agent_ai_BonsaiInference_unloadModel(JNIEnv*, jobject) {
    if (!g_loaded) return;
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model)   { llama_free_model(g_model); g_model = nullptr; }
    llama_backend_free();
    g_loaded = false;
    LOGI("Bonsai unloaded");
}

JNIEXPORT jboolean JNICALL
Java_com_blaze_agent_ai_BonsaiInference_isLoaded(JNIEnv*, jobject) { return (jboolean)g_loaded; }

} // extern "C"
