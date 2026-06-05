const questions = {
    1: {
        text: "Question 1",
        answers: {
            A: {
                text: "Answer 1",
                cuteness: 10,
                coolness: -10
            },
            B: {
                text: "Answer 2",
                cuteness: -10,
                coolness: 10
            }   
        },
    },
    2: {
        text: "Question 2",
        answers: {
            A: {
                text: "Answer 1",
                cuteness: 10,
                coolness: -10
            },
            B: {
                text: "Answer 2",
                cuteness: -10,
                coolness: 10
            }
        }
    }
}

// console.log(questions[1].answers.A.cuteness);

for (item of questions) {
    console.log(item.text);
    console.log(item.answers);
}

var answerKey = [];
var cutenessScore = 0;
var coolnessScore = 0;

// 回答を集めて

for (item of answerKey) {
    cutenessScore += item.cuteness;
    coolnessScore += item.coolness;
}