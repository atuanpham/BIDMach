package BIDMach.allreduce

import BIDMach.Learner
import BIDMach.models.{GLM, Model}
import BIDMach.networks.Net
import BIDMat.{FMat, Mat}
import BIDMat.MatFunctions._
import BIDMat.SciFunctions._
import akka.actor.{Actor, ActorLogging}

class AllreduceTrainer(learner: Learner) extends Actor with ActorLogging {
  override def receive: Receive = {
    case StartTraining => {
      log.info("start training")
      learner.train
      log.info("end training")
    }
  }
}

object AllreduceTrainer {

  // Adjust to point to the BIDMach/data/rcv1 directory
  // This dataset and several others are loaded by running the script getdata.sh

  def basicClassification(dirRcv: String = "./data/rcv1/"): Learner = {

    val train = loadSMat(dirRcv + "docs.smat.lz4")
    val cats = loadFMat(dirRcv + "cats.fmat.lz4")

    val (mm, opts) = GLM.learner(train, cats, GLM.logistic)
    opts.lrate = 0.3f
    opts.npasses = 2
    println("Classification model: ", mm)
    mm
  }

  def predictClassification(model: Model, dirRcv: String = "./data/rcv1/") = {

    val test = loadSMat(dirRcv + "testdocs.smat.lz4")
    val tcats = loadFMat(dirRcv + "testcats.fmat.lz4")
    val (pp, popts) = GLM.predictor(model, test)
    pp.predict
    val preds = FMat(pp.preds(0))
    val rocs = roc2(preds, tcats, 1-tcats, 100)
    val aucs = mean(rocs)
    print(s"Aucs: $aucs")

  }

  def leNetModel(): Learner = {
    Mat.checkCUDA(true)
    val traindir = "/data/MNIST/"
    val train0 = loadIDX(traindir + "train-images-idx3-ubyte.gz").reshapeView(1, 28, 28, 60000);
    val trainlabels0 = loadIDX(traindir + "train-labels-idx1-ubyte.gz").reshapeView(1, 60000);
    val test = loadIDX(traindir + "t10k-images-idx3-ubyte.gz").reshapeView(1, 28, 28, 10000);
    val testlabels = loadIDX(traindir + "t10k-labels-idx1-ubyte.gz").reshapeView(1, 10000);

    val rp = randperm(60000);
    val train = train0(?, ?, ?, rp);
    val trainlabels = trainlabels0(?, rp);
    val mt = train.mean(irow(3));
    train ~ train - mt;
    test ~ test - mt;

    val (nn, opts) = Net.learner(train, trainlabels);

    //val convt = jcuda.jcudnn.cudnnConvolutionMode.CUDNN_CROSS_CORRELATION
    val convt = 0

    opts.batchSize = 64
    opts.npasses = 20

    opts.lrate = 1e-3f
    opts.texp = 0.3f
    opts.pstep = 0.1f
    opts.hasBias = true;
    opts.tensorFormat = Net.TensorNCHW;
    //opts.autoReset = false;

    import BIDMach.networks.layers.Node._;

    val in = input();

    val conv1 = conv(in)(w = 5, h = 5, nch = 20, stride = 1, pad = 0, initv = 0.01f, convType = convt);
    val pool1 = pool(conv1)(w = 2, h = 2, stride = 2);

    val conv2 = conv(pool1)(w = 5, h = 5, nch = 20, stride = 1, pad = 0, convType = convt);
    val pool2 = pool(conv2)(w = 2, h = 2, stride = 2);

    val fc3 = linear(pool2)(outdim = 500, initv = 3e-2f);
    val relu3 = relu(fc3)();

    val fc4 = linear(relu3)(outdim = 10, initv = 5e-2f);
    //val out = softmaxout(fc4)();
    val out = softmaxout(fc4)(scoreType = 1);

    val nodes = (in \ null on
      conv1 \ pool1 on
      conv2 \ pool2 on
      fc3 \ relu3 on
      fc4 \ out).t
    opts.nodemat = nodes
    nn


  }
}