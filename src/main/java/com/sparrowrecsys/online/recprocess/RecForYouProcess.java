package com.sparrowrecsys.online.recprocess;

import com.sparrowrecsys.online.datamanager.DataManager;
import com.sparrowrecsys.online.datamanager.User;
import com.sparrowrecsys.online.datamanager.Movie;
import com.sparrowrecsys.online.datamanager.RedisClient;
import com.sparrowrecsys.online.util.Config;
import com.sparrowrecsys.online.util.Utility;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

import static com.sparrowrecsys.online.util.HttpClient.asyncSinglePostRequest;

/**
 * Recommendation process of similar movies
 */

public class RecForYouProcess {

    /**
     * get recommendation movie list
     * @param userId input user id
     * @param size  size of similar items
     * @param model model used for calculating similarity
     * @return  list of similar movies
     */
    public static List<Movie> getRecList(int userId, int size, String model){
        User user = DataManager.getInstance().getUserById(userId);
        if (null == user){
            return new ArrayList<>();
        }
//        final int CANDIDATE_SIZE = 800;
//        List<Movie> candidates = DataManager.getInstance().getMovies(CANDIDATE_SIZE, "rating");


        //load user emb from redis if data source is redis
        if (Config.EMB_DATA_SOURCE.equals(Config.DATA_SOURCE_REDIS)){
            String userEmbKey = "uEmb:" + userId;
            String userEmb = RedisClient.getInstance().get(userEmbKey);
            if (null != userEmb){
                user.setEmb(Utility.parseEmbStr(userEmb));
            }
        }

        if (Config.IS_LOAD_USER_FEATURE_FROM_REDIS){
            String userFeaturesKey = "uf:" + userId;
            Map<String, String> userFeatures = RedisClient.getInstance().hgetAll(userFeaturesKey);
            if (null != userFeatures){
                user.setUserFeatures(userFeatures);
            }
        }

        List<Movie> candidates = multipleRetrievalCandidates(user);
        List<Movie> rankedList = ranker(user, candidates, model);

        if (rankedList.size() > size){
            return rankedList.subList(0, size);
        }
        return rankedList;
    }
    /**
     * multiple-retrieval candidate generation method
     * @param user input movie object
     * @return movie candidates
     */
    public static List<Movie> multipleRetrievalCandidates(User user){
        if (null == user){
            return null;
        }

        HashSet<String> genres = new HashSet<>();
        genres.add(user.getUserFeatures().get("userGenre1"));
        genres.add(user.getUserFeatures().get("userGenre2"));
        genres.add(user.getUserFeatures().get("userGenre3"));
        genres.add(user.getUserFeatures().get("userGenre4"));
        genres.add(user.getUserFeatures().get("userGenre5"));

        HashMap<Integer, Movie> candidateMap = new HashMap<>();
        for (String genre : genres){
            List<Movie> oneCandidates = DataManager.getInstance().getMoviesByGenre(genre, 20, "rating");
            for (Movie candidate : oneCandidates){
                candidateMap.put(candidate.getMovieId(), candidate);
            }
        }

        List<Movie> highRatingCandidates = DataManager.getInstance().getMovies(100, "rating");
        for (Movie candidate : highRatingCandidates){
            candidateMap.put(candidate.getMovieId(), candidate);
        }

        List<Movie> latestCandidates = DataManager.getInstance().getMovies(100, "releaseYear");
        for (Movie candidate : latestCandidates){
            candidateMap.put(candidate.getMovieId(), candidate);
        }

        return new ArrayList<>(candidateMap.values());
    }

    /**
     * rank candidates
     * @param user    input user
     * @param candidates    movie candidates
     * @param model     model name used for ranking
     * @return  ranked movie list
     */
    public static List<Movie> ranker(User user, List<Movie> candidates, String model){
        HashMap<Movie, Double> candidateScoreMap = new HashMap<>();

        switch (model){
            case "emb":
                for (Movie candidate : candidates){
                    double similarity = calculateEmbSimilarScore(user, candidate);
                    candidateScoreMap.put(candidate, similarity);
                }
                break;
            case "neuralcf":
                callNeuralCFTFServing(user, candidates, candidateScoreMap);
                break;
            case "din":
                callDinTFServing(user, candidates, candidateScoreMap);
            default:
                //default ranking in candidate set
                for (int i = 0 ; i < candidates.size(); i++){
                    candidateScoreMap.put(candidates.get(i), (double)(candidates.size() - i));
                }
        }

        List<Movie> rankedList = new ArrayList<>();
        candidateScoreMap.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).forEach(m -> rankedList.add(m.getKey()));
        return rankedList;
    }

    /**
     * function to calculate similarity score based on embedding
     * @param user     input user
     * @param candidate candidate movie
     * @return  similarity score
     */
    public static double calculateEmbSimilarScore(User user, Movie candidate){
        if (null == user || null == candidate || null == user.getEmb()){
            return -1;
        }
        return user.getEmb().calculateSimilarity(candidate.getEmb());
    }

    /**
     * call TenserFlow serving to get the NeuralCF model inference result
     * @param user              input user
     * @param candidates        candidate movies
     * @param candidateScoreMap save prediction score into the score map
     */
    public static void callNeuralCFTFServing(User user, List<Movie> candidates, HashMap<Movie, Double> candidateScoreMap){
        if (null == user || null == candidates || candidates.size() == 0){
            return;
        }

        JSONArray instances = new JSONArray();
        for (Movie m : candidates){
            JSONObject instance = new JSONObject();
            instance.put("userId", user.getUserId());
            instance.put("movieId", m.getMovieId());
            instances.put(instance);
        }

        JSONObject instancesRoot = new JSONObject();
        instancesRoot.put("instances", instances);

        //need to confirm the tf serving end point
        String predictionScores = asyncSinglePostRequest("http://localhost:8501/v1/models/recmodel:predict", instancesRoot.toString());
        System.out.println("send user" + user.getUserId() + " request to tf serving.");

        JSONObject predictionsObject = new JSONObject(predictionScores);
        JSONArray scores = predictionsObject.getJSONArray("predictions");
        for (int i = 0 ; i < candidates.size(); i++){
            candidateScoreMap.put(candidates.get(i), scores.getJSONArray(i).getDouble(0));
        }
    }

    public static void callDinTFServing(User user, List<Movie> candidates, HashMap<Movie, Double> candidateScoreMap){
        if (null == user || null == candidates || candidates.size() == 0){
            return;
        }

        JSONArray instances = new JSONArray();
        for (Movie m : candidates){
            JSONObject instance = new JSONObject();
            instance.put("userId", user.getUserId());
            instance.put("userAvgRating", Float.parseFloat(user.getUserFeatures().get("userAvgRating")));
            instance.put("userRatingStddev",Float.parseFloat(user.getUserFeatures().get("userRatingStddev")));
            instance.put("userRatingCount", Integer.parseInt(user.getUserFeatures().get("userRatingCount")));

            instance.put("userRatedMovie1", Integer.parseInt(user.getUserFeatures().get("userRatedMovie1")));
            instance.put("userRatedMovie2", Integer.parseInt(user.getUserFeatures().get("userRatedMovie2")));
            instance.put("userRatedMovie3", Integer.parseInt(user.getUserFeatures().get("userRatedMovie3")));
            instance.put("userRatedMovie4", Integer.parseInt(user.getUserFeatures().get("userRatedMovie4")));
            instance.put("userRatedMovie5", Integer.parseInt(user.getUserFeatures().get("userRatedMovie5")));

            instance.put("userGenre1", user.getUserFeatures().get("userGenre1"));
            instance.put("userGenre2", user.getUserFeatures().get("userGenre2"));
            instance.put("userGenre3", user.getUserFeatures().get("userGenre3"));
            instance.put("userGenre4", user.getUserFeatures().get("userGenre4"));
            instance.put("userGenre5", user.getUserFeatures().get("userGenre5"));

            instance.put("movieId", m.getMovieId());
            instance.put("movieAvgRating", Float.parseFloat(m.getMovieFeatures().get("movieAvgRating")));
            instance.put("movieRatingStddev", Float.parseFloat(m.getMovieFeatures().get("movieRatingStddev")));
            instance.put("movieRatingCount",Integer.parseInt(m.getMovieFeatures().get("movieRatingCount")));
            instance.put("releaseYear", Integer.parseInt(m.getMovieFeatures().get("releaseYear")));
            instance.put("movieGenre1", m.getMovieFeatures().get("movieGenre1"));
            instance.put("movieGenre2", m.getMovieFeatures().get("movieGenre2"));
            instance.put("movieGenre3", m.getMovieFeatures().get("movieGenre3"));
            instances.put(instance);
        }

        JSONObject instancesRoot = new JSONObject();
        instancesRoot.put("instances", instances);

        //need to confirm the tf serving end point
        String predictionScores = asyncSinglePostRequest("http://localhost:8501/v1/models/dinmodel:predict", instancesRoot.toString());
        System.out.println("send user" + user.getUserId() + " request to din tf serving.");

        JSONObject predictionsObject = new JSONObject(predictionScores);
        JSONArray scores = predictionsObject.getJSONArray("predictions");
        for (int i = 0 ; i < candidates.size(); i++){
            candidateScoreMap.put(candidates.get(i), scores.getJSONArray(i).getDouble(0));
        }
    }

}

