package com.dicoding.newsapp.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.dicoding.newsapp.BuildConfig
import com.dicoding.newsapp.data.local.entity.NewsEntity
import com.dicoding.newsapp.data.local.room.NewsDao
import com.dicoding.newsapp.data.remote.response.NewsResponse
import com.dicoding.newsapp.data.remote.retrofit.ApiService
import com.dicoding.newsapp.utils.AppExecutors
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// untuk menyimpan data dari network ke local
class NewsRepository private constructor(
    private val apiService: ApiService,
    private val newsDao: NewsDao,
    private val appExecutors: AppExecutors
) {
    private val result = MediatorLiveData<Result<List<NewsEntity>>>() //komponen ini digunakan jika Anda ingin menggabungkan banyak sumber data dalam sebuah LiveData

    fun getHeadlineNews(): LiveData<Result<List<NewsEntity>>> {
        result.value = Result.Loading // Inisiasi dengan status Loading.
        val client = apiService.getNews(BuildConfig.API_KEY) // Mengambil dari network dengan ApiService.

        client.enqueue(object : Callback<NewsResponse> {
            override fun onResponse(call: Call<NewsResponse>, response: Response<NewsResponse>) {
                if (response.isSuccessful) { // Membaca data ketika response berhasil
                    val articles = response.body()?.articles
                    val newsList = ArrayList<NewsEntity>()
                    appExecutors.diskIO.execute {
                        articles?.forEach { article ->
                            val isBookmarked = newsDao.isNewsBookmarked(article.title) // Mengecek apakah data yang ada sudah ada di dalam bookmark atau belum.
                            val news = NewsEntity(
                                article.title,
                                article.publishedAt,
                                article.urlToImage,
                                article.url,
                                isBookmarked
                            )
                            newsList.add(news) // Mengubah data response menjadi entity sebelum dimasukkan ke dalam database.
                        }
                        newsDao.deleteAll() // Menghapus semua data dari database yang tidak ditandai bookmark
                        newsDao.insertNews(newsList) // Memasukkan data baru dari internet ke dalam database
                    }
                }
            }

            override fun onFailure(call: Call<NewsResponse>, t: Throwable) {
                result.value = Result.Error(t.message.toString()) // Memberi status jika terjadi eror
            }
        })
        val localData = newsDao.getNews()// Mengambil data dari database yang merupakan sumber utama untuk dikonsumsi dan memberi tanda sukses.
        result.addSource(localData) { newData: List<NewsEntity> ->
            result.value = Result.Success(newData)
        }
        return result
    }

    //  mengatur dan mengambil bookmark
    fun getBookmarkedNews(): LiveData<List<NewsEntity>> {
        return newsDao.getBookmarkedNews()
    }

    fun setBookmarkedNews(news: NewsEntity, bookmarkState: Boolean) {
        appExecutors.diskIO.execute {
            news.isBookmarked = bookmarkState
            newsDao.updateNews(news)
        }
    }

    companion object {
        @Volatile
        private var instance: NewsRepository? = null
        fun getInstance(
            apiService: ApiService,
            newsDao: NewsDao,
            appExecutors: AppExecutors
        ): NewsRepository =
            instance ?: synchronized(this) {
                instance ?: NewsRepository(apiService, newsDao, appExecutors)
            }.also { instance = it }
    }
}