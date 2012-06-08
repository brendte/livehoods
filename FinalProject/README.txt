==Introduction==

This application is an attempt to re-implement the Livehoods spectral clustering algorithm detailed in the included paper "The Livehoods Project: Utilizing Social Media to Understand the Dynamics of a City," (Cranshaw, Schawartz, Hong and Sadeh. 2012.) on a Twitter data set collected in May 2012. It includes some modifications to the original data and algorithm used by the Livehoods researchers. as discussed below.

==How to Run==

Ruby data collection and processing code: The tweet collector code (see Data Collection below) is on Heroku and could be run if needed. I'd be happy to do that if you want, just let me know. The data processing was all interactively via the Ruby REPL using the classes discussed below.

Java affinity matrix and spectral clustering code: You don't want to do this. It will take a very long time. If you really want to, however, you can just click the Run button in Eclipse and watch your computer melt.  You may need to add the three jars in lib/ to your build path. 

==Data Collection==

Data was collected from the Twitter Streaming API using a script written in Ruby utilizing the EventMachine framework.  The running application is hosted on Heroku, and the code repo is on Github (https://github.com/brendte/livehoods-twitter). Over the course of approximately a week, I was able to collect 321,525 tweets that contained geographic coordinates that placed their origin within the bounding box [-75.280327,39.864841,-74.941788,40.154541] around the city of Philadelphia.  Interestingly, only about 60% of the collected tweets actually came from within this bounding box, so I had to run an initial pass on the data to pull out any such tweet.  The data set is stored in a hosted MongoDB database at MongoLabs.  Access to the dataset is available on request. See https://github.com/brendte/livehoods-twitter/blob/master/twitter_stream.rb and https://github.com/brendte/livehoods-twitter/blob/master/tweet_listener.rb.

==Data Processing==

**Map Grid**

The original work on Livehoods used only tweets that originated from Foursquare so that they contained Foursquare venue data with which to work. My initial tests on collecting the same data for Philadelphia showed that it was unnecessarily restrictive to do so.  Thus, I opted to collect all geo-tagged tweets within the Philadelphia bounding box. This required an adjustment to the Livehoods procedure, however. Since we no longer had data that represented a user's checkin at a specific venue via Foursquare, I elected to treat every tweet as a "check-in" to a "venue" represented by 0.1 x 0.1 mile box in a grid overlaid on the Philadelphia bounding box. The grid contains 36,514 such boxes.  See https://github.com/brendte/livehoods-twitter/blob/master/city_grid.rb.

After the grid was created, the set of tweets was processed in order to assign each tweet to the box in the grid in which it is contained.  I used MongoDB's native geolocation indexing functionality to accomplish this.  The basic procedure is: for each box, send the box's coordinates to MongoDB, and ask it to return all of the tweets that have coordinates that fall within this box. See https://github.com/brendte/livehoods-twitter/blob/master/city_tweets.rb

**Users**

Once the tweets were collected, the data was processed to collect the set of all unique users whose tweets appear in the data set. Following the Livehoods methodology, this is necessary in order to create the "bag of checkins" at each venue, represented by a "vector c_v, where the uth component of c_v is the number of times user u checked into venue v" (Cranshaw, Schawartz, Hong and Sadeh. 2012. 2).  There were a total of 24,801 users represented in the data set. See https://github.com/brendte/livehoods-twitter/blob/master/city_tweets.rb.

After the users were collected and assigned an internal user ID, the set of tweets was processed to map the twitter ID to our user ID. See https://github.com/brendte/livehoods-twitter/blob/master/city_user.rb.

**Bag of Checkins**

We now effectively have our bag of checkins for each venue: each tweet is assigned to a map grid box (the venue) and a user.  At this point, the initial data processing it complete.  In order to simplify later processing, we take this bag of checkins and store it in a hash on each grid box in the database. See https://github.com/brendte/livehoods-twitter/blob/master/city.rb.

==Livehoods Algorithm==

The Spectral Clustering code is written in Java, and is contained in this workspace (Cluster.java).  It utilizes the MongoDB Java driver for accessing the data in MongoDB, and the Colt matrix and linear algebra libraries.

**Affinity Matrix**

I first read in the bag of checkins for each grid box from MongoDB, and turn it into a vector.  The vector is then added to a list for iterating over. To create the actual affinity matrix A, the code uses nested looping.  The outer loop reads each row i for use as the first vector in the cosine similarity calculation and the inner loop reads each row j as the second vector in the cosine similarity calculation.  For each row i the bag of checkins list, all j rows in the same bag of checkins list are read. The cosine similarity of i and j is calculated for each i and j, and the result is stored in the affinity matrix at location (i, j). This value represents how closely related the two venues are in terms of the similarity of their bags of checkins (in other words, the similarity of the number of checkins by particular users to a particular venue).  Once completed, A is serialized and stored as a file.   

Of note, I had to ensure that both i and j have at least 1 element > 0.0, otherwise the calculation results in a divide by zero error. This served also to speed up the creation of A, since no cosine similarity calculation is performed if either i or j has all elements = 0.0. Even with this optimization, however, the speed of this calculation was very slow, considering that it loops approximately 36500 x 36500 times (it ran for over 72 hours on my dual-core i7 MacBook Air with 4GB of RAM). It's likely that I should have used the sparseness of the matrix to my advantage, and only even read in any rows that were non-zero. This would likely have significantly improved the performance. Additionally, I need to do more research into efficient matrix manipulation and calculation algorithms, as there are probably much better ways to do this calculation than the naive double-looping used here.

When this was completed, there were approximately 947,000 non-zero entries in the affinity matrix.

**Spectral Clustering**
NOTE: All spectral clustering calculations were run on a 4xExtraLarge AWS EC2 instance, with 68G of RAM, as the matrix calculations required more heap space than my 4G MacBook Air could provide.

Using affinity matrix A, we calculate the diagonal degree matrix D of A, which is the sum of all affinity values j for row i. This is equivalent to calculating the "in weight" of all the edges into node i.  This value is stored at D(i, i), which is a sparse matrix containing only values on the diagonal. Once D is built, it is serialized and stored as a file. As one would expect, this calculation ran in only a few minutes.

When this was completed, there were approximately 15,000 non-zero entries in D.

With D built, we then calculate L = D - A, which is then used to calculate L_norm = D^-1/2*L*D^-1/2. Once L_norm is built, it is serialized and stored as a file.

With L_norm built, we can finally calculate the eigenvalues and eigenvectors, which is the whole point of all of this! I use Colt's built-in EigenvalueDecomposition class to do the eigen calculations.  To find the k_max smallest eigenvalues, I sort the eigenvalues array least to greatest, and then pull off the first k_max values.  I then find the largest delta = eigenval_i+1 - eigenval_i, where k_min <= i < k_max, and use that delta's i value as k. This k value is then used to pull off the k smallest eigenvectors of L_norm.  Finally, we take the k smallest eigenvectors of L_norm, represented as rows of a matrix, and flip the matrix so the eigenvectors are now columns of a new matrix E. Once E is built, it is serialized and stored as a file.

==What's Missing?==

Unfortunately, the final output. I have simply run out of time to complete the full spectral clustering algorithm code.  The remaining work is as follows:

1) Run k-means clustering on matrix E (the k smallest eigenvectors of L_norm). I will use the Weka machine learning libraries to do this, specifically the weka.clusterers.SimpleKMeans class. In order to do this, E will first have to be stored in a format usable by Weka, which requires creating an Instance object for each row in E and adding it to an Instances collection.
2) Output the clusters decided by Weka to a file that contains an ID for each cluster, and all of the box ids (the row number for the vector representing the box in E) in that cluster.
3) Read in this file, map each cluster ID to a distinct color (for placing on the map) and map each box id to it coordinates (as stored in MongoDB)
4) For each box in the cluster file, map it as a polygon onto a Google Map of Philadelphia using the box's coordinates. The outline and shading color will match the color for the cluster to which the box belongs.
5) Refactoring and cleaning up the code.  The Ruby code needs basic housekeeping, and could be a bit more modular.  The Java code needs a ton of refactoring to make it object-oriented.  Right now, it is strictly procedural.  My real preference here (and as I said I wanted to do in my progress report) would be to do all of this in Clojure using the Incanter libraries, so that it would all be functional rather than procedural/object-oriented. I just ran out of time to learn Incanter, though...
	
==Lessons Learned==

1) It takes much longer to collect process a large, usable dataset than I anticipated. I spent probably 100 hours over the course of a month just to build the data set to use for this project.  It was literally a project on its own!
2) Matrix calculations, even on machine with lots of memory and CPU, using matrix and linear algebra libraries written and optimized by people far smarter than I,
take A VERY LONG TIME. I gave myself a week to run the calculations.  I needed at least two, but probably more.
3) Pay attention to the sparseness of your matrices. Whenever you can, make sure to compact your dense matrices, so that later calculations can be performed on them
using algorithms that are only efficient on sparse matrices.
4) Naive nested looping operations on large matrices are a BAD IDEA. While I certainly understood the time and memory complexity of doing something like this from 
a theoretical standpoint, I wasn't really prepared for what it meant in practice. It's very easy to become complacent these days with all the memory and CPU
horsepower at your disposal, but when you're doing stuff like doubly nested loops over a 36000 x 36000 element matrix, it's still going to take a very long time to run.

==Open Questions==

The Livehoods paper claims that "by only connecting each venue v to its m nearest neighbors in geographic distances, it allows us to keep the matrices extremely sparse, enabling us to scale to process hundreds of thousands of venues without any need for parallelization." 
1) Does this bias the clustering to be geographically oriented by a neighborhood, thus potentially negating the social aspect of Livehoods we are actually trying to find?
2) Did my use of denser matrices lead to the poor performance I observed?
3) What parts of my algorithms are inefficiently implemented in comparison to the Livehoods investigators that allowed them to make this claim, while my results would indicate that this method cannot be run efficiently without significant parallelization and processing horsepower?
4) Are there better matrix and linear algebra libraries than Colt for this type of numerical processing? (Seems like Colt is highly optimized and efficient, so I'd be surprised if this were the case, at least in Java).
5) Is there a more appropriate language to use for this? (These days, Java is so fast that I find it hard to imagine doing this in Fortran or C would yield significant performance improvements, but I could be very wrong.)
6) How could this be converted to a parallelized algorithm (to run on a Hadoop cluster, for instance)?
7) How could this be converted to Clojure/Incanter?
8) How could we use Mahout for k-means rather than Weka? 


