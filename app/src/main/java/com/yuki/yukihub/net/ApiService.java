package com.yuki.yukihub.net;

import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

/**
 * 通用 REST API Retrofit 接口。
 * <p>
 * 适用于 VNDB、Bangumi、月幕 Gal、AI Review 等 JSON API 场景。
 * 路径均使用 @Url 动态指定，由各 Client 拼接完整 URL。
 */
public interface ApiService {

    @GET
    Call<ResponseBody> get(@Url String url);

    @GET
    Call<ResponseBody> getWithHeader(@Url String url, @Header("Authorization") String auth);

    @POST
    Call<ResponseBody> post(@Url String url, @Body RequestBody body);

    @POST
    Call<ResponseBody> postWithAuth(@Url String url, @Body RequestBody body,
                                    @Header("Authorization") String auth);
}
